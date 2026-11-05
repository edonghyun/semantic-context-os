package io.scos.connector.jira.internal;

import static io.scos.connector.jira.internal.JiraAccessResponses.array;
import static io.scos.connector.jira.internal.JiraAccessResponses.id;
import static io.scos.connector.jira.internal.JiraAccessResponses.incomplete;
import static io.scos.connector.jira.internal.JiraAccessResponses.text;
import static io.scos.connector.jira.internal.JiraApiClient.segment;

import io.scos.connector.jira.internal.JiraGrantGroups.Grant;
import io.scos.spi.access.AccessKey;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** run 하나의 Jira 권한 해석. 호출자는 유형·holder·페이지·공유 수준의 수집 순서를 알 필요가 없다. */
final class JiraAccessDirectory {
  private static final Set<String> SUPPORTED_HOLDERS =
      Set.of(
          "user",
          "projectLead",
          "projectRole",
          "group",
          "reporter",
          "assignee",
          "userCustomField",
          "groupCustomField");

  enum ProjectStyle {
    COMPANY_MANAGED,
    TEAM_MANAGED,
    UNKNOWN
  }

  enum Decision {
    SUPPORTED,
    POLICY_BLOCKED,
    OBSERVATION_FAILED
  }

  record IssueGrant(String type, String fieldId) {}

  /** decision은 Browse의 판정이다. levelFailure가 있으면 제한 이슈의 수준은 미확정이다. */
  record ProjectAccess(
      String projectId,
      ProjectStyle style,
      Decision decision,
      Optional<Grant> projectGrant,
      Map<String, Grant> levels,
      Set<IssueGrant> issueGrants,
      AclPage observations,
      Optional<CallFailure> failure,
      Optional<CallFailure> levelFailure) {
    ProjectAccess {
      levels = Map.copyOf(levels);
      issueGrants = Set.copyOf(issueGrants);
      if ((decision == Decision.SUPPORTED) != projectGrant.isPresent()
          || (decision == Decision.OBSERVATION_FAILED) != failure.isPresent())
        throw new IllegalArgumentException("Inconsistent Jira project decision");
      if (decision == Decision.SUPPORTED && style != ProjectStyle.COMPANY_MANAGED)
        throw new IllegalArgumentException("Unsupported Jira project grant");
      if (decision != Decision.SUPPORTED
          && (!levels.isEmpty() || !issueGrants.isEmpty() || levelFailure.isPresent()))
        throw new IllegalArgumentException("Unavailable Jira project has access details");
      if (levelFailure.isPresent() && !levels.isEmpty())
        throw new IllegalArgumentException("Incomplete Jira levels have grants");
    }

    Set<String> browseUsers() {
      return projectGrant.map(g -> g.snapshot().members()).orElse(Set.of());
    }

    void emitTo(AclSink sink) {
      sink.accept(observations);
    }
  }

  private record Browse(
      JsonNode project, Set<String> users, Set<IssueGrant> dynamic, boolean policyBlocked) {}

  private final AccessInput input;
  private final Clock clock;
  private final JiraAccessResponses http;
  private final JiraGrantGroups grants;
  private final Map<String, ProjectStyle> observedStyles = new LinkedHashMap<>();
  private final Map<String, Browse> projects = new LinkedHashMap<>();
  private final Map<String, ProjectAccess> results = new LinkedHashMap<>();
  private final Map<String, SourceIdentityRecord> identities = new LinkedHashMap<>();
  private final List<SourceIdentityRecord> conflictingIdentities = new ArrayList<>();
  private final Map<String, GroupSnapshot> groups = new LinkedHashMap<>();

  JiraAccessDirectory(
      AccessInput input,
      JiraConfiguration config,
      String token,
      JiraApiClient client,
      Clock clock) {
    this.input = input;
    this.clock = clock;
    http = new JiraAccessResponses(input, config, token, client);
    grants = new JiraGrantGroups(input.source().id());
  }

  ProjectAccess readProject(String projectId) {
    requireActive();
    return results.computeIfAbsent(projectId, this::attemptProject);
  }

  /** 호출자가 독립 프로젝트를 모두 처리한 뒤 한 번 확인한다. 정책 차단은 완료를 방해하지 않는다. */
  void requireComplete() {
    requireActive();
    for (ProjectAccess project : results.values()) {
      Optional<CallFailure> failure = project.failure().or(project::levelFailure);
      if (failure.isPresent()) throw new CallFailureException("jira.access", failure.orElseThrow());
    }
  }

  private void requireActive() {
    if (input.cancel().isCancelled())
      throw new CallFailureException(
          "jira.access", CallFailure.of(CallFailure.Kind.CANCELLED, "Jira access cancelled"));
  }

  AclPage observations() {
    Map<String, GroupSnapshot> snapshots = new LinkedHashMap<>();
    groups.values().forEach(g -> snapshots.put(g.groupId(), g));
    for (ProjectAccess project : results.values())
      project.observations().groups().forEach(g -> snapshots.put(g.groupId(), g));
    return observations(List.copyOf(snapshots.values()));
  }

  String issueFields(ProjectAccess project) {
    return project.issueGrants().stream()
        .map(IssueGrant::fieldId)
        .filter(id -> !id.isEmpty())
        .sorted()
        .distinct()
        .collect(Collectors.joining(","));
  }

  Set<AccessKey> issueKeys(ProjectAccess project, JsonNode issue) {
    JsonNode fields = issue.path("fields");
    if (!fields.has("security")) throw incomplete("Missing Jira issue security");
    JsonNode security = fields.path("security");
    if (!security.isNull()) {
      Grant level = project.levels().get(id(security, "id"));
      if (level == null) throw incomplete("Unobserved Jira issue security level");
      return Set.of(level.key());
    }
    Set<AccessKey> keys = new LinkedHashSet<>();
    keys.add(project.projectGrant().orElseThrow().key());
    for (IssueGrant grant : project.issueGrants()) {
      for (String account : issueUsers(grant, issue)) {
        user(account);
        SourceIdentityRecord identity = Objects.requireNonNull(identities.get(account));
        if (!identity.guest() && !identity.deleted())
          keys.add(new AccessKey(AccessKey.Kind.USER, input.source().id(), account));
      }
    }
    return Set.copyOf(keys);
  }

  private Set<String> issueUsers(IssueGrant grant, JsonNode issue) {
    String field = grant.fieldId().isEmpty() ? grant.type() : grant.fieldId();
    JsonNode value = issue.path("fields").path(field);
    if (value.isMissingNode()) throw incomplete("Missing Jira granted issue field");
    if (grant.type().equals("reporter") || grant.type().equals("assignee"))
      return value.isNull() ? Set.of() : Set.of(text(value, "accountId"));
    JsonNode schema = issue.path("schema").path(field);
    if (!field.equals("customfield_" + id(schema, "customId")))
      throw incomplete("Conflicting Jira custom field id");
    String expected = grant.type().equals("userCustomField") ? "user" : "group";
    String type = text(schema, "type");
    boolean multiple = type.equals("array");
    if (!(multiple ? text(schema, "items") : type).equals(expected))
      throw incomplete("Conflicting Jira custom field schema");
    if (value.isNull()) return Set.of();
    if (multiple && !value.isArray()) throw incomplete("Missing Jira custom field array");
    Set<String> users = new LinkedHashSet<>();
    Iterable<JsonNode> values = multiple ? value : List.of(value);
    for (JsonNode member : values) {
      if (expected.equals("user")) users.add(text(member, "accountId"));
      else users.addAll(groupUsers(member));
    }
    return Set.copyOf(users);
  }

  private ProjectAccess attemptProject(String projectId) {
    try {
      return observeProject(projectId);
    } catch (CallFailureException failure) {
      if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
      ProjectStyle style = observedStyles.getOrDefault(projectId, ProjectStyle.UNKNOWN);
      return new ProjectAccess(
          projectId,
          style,
          Decision.OBSERVATION_FAILED,
          Optional.empty(),
          Map.of(),
          Set.of(),
          observations(List.copyOf(groups.values())),
          Optional.of(failure.failure()),
          Optional.empty());
    }
  }

  private ProjectAccess observeProject(String projectId) {
    JsonNode projectDetails = project(projectId);
    ProjectStyle style = style(projectDetails);
    String product = text(projectDetails, "projectTypeKey");
    if (style == ProjectStyle.UNKNOWN) throw incomplete("Unknown Jira project style");
    if (style == ProjectStyle.TEAM_MANAGED || !supportedProduct(product))
      return unavailable(projectId, style, Decision.POLICY_BLOCKED);
    Browse browse = browse(projectId);
    if (browse.policyBlocked()) return unavailable(projectId, style, Decision.POLICY_BLOCKED);
    Map<String, Grant> levels = Map.of();
    Optional<CallFailure> levelFailure = Optional.empty();
    try {
      levels = levels(projectId);
    } catch (CallFailureException failure) {
      if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
      levelFailure = Optional.of(failure.failure());
    }
    Instant at = clock.instant();
    Grant project = grants.project(projectId, browse.users(), at);
    List<GroupSnapshot> snapshots = new ArrayList<>(groups.values());
    snapshots.add(project.snapshot());
    levels.values().forEach(level -> snapshots.add(level.snapshot()));
    return new ProjectAccess(
        projectId,
        ProjectStyle.COMPANY_MANAGED,
        Decision.SUPPORTED,
        Optional.of(project),
        levels,
        browse.dynamic(),
        observations(snapshots),
        Optional.empty(),
        levelFailure);
  }

  private JsonNode project(String projectId) {
    JsonNode project = http.read("/project/" + segment(projectId));
    if (!id(project, "id").equals(projectId)) throw incomplete("Conflicting Jira project id");
    observedStyles.put(projectId, style(project));
    return project;
  }

  private ProjectStyle style(JsonNode project) {
    if (!project.path("simplified").isBoolean()) return ProjectStyle.UNKNOWN;
    String style = project.path("style").asString("");
    boolean simplified = project.path("simplified").asBoolean();
    if (style.equals("classic") && !simplified) return ProjectStyle.COMPANY_MANAGED;
    if (style.equals("next-gen") && simplified) return ProjectStyle.TEAM_MANAGED;
    return ProjectStyle.UNKNOWN;
  }

  private boolean supportedProduct(String product) {
    return product.equals("software") || product.equals("business");
  }

  private AclPage observations(List<GroupSnapshot> snapshots) {
    List<SourceIdentityRecord> users = new ArrayList<>(conflictingIdentities);
    users.addAll(identities.values());
    return new AclPage(Map.of(), snapshots, users);
  }

  private ProjectAccess unavailable(String project, ProjectStyle style, Decision decision) {
    return new ProjectAccess(
        project,
        style,
        decision,
        Optional.empty(),
        Map.of(),
        Set.of(),
        new AclPage(Map.of(), List.of(), List.of()),
        Optional.empty(),
        Optional.empty());
  }

  private Browse browse(String projectId) {
    return projects.computeIfAbsent(projectId, this::readBrowse);
  }

  private Browse readBrowse(String projectId) {
    JsonNode project = project(projectId);
    if (style(project) != ProjectStyle.COMPANY_MANAGED
        || !supportedProduct(text(project, "projectTypeKey")))
      throw incomplete("Unobservable Jira shared level project");
    String schemeId = id(http.read("/project/" + segment(projectId) + "/permissionscheme"), "id");
    JsonNode scheme = http.read("/permissionscheme/" + segment(schemeId) + "?expand=permissions");
    if (!id(scheme, "id").equals(schemeId)) throw incomplete("Conflicting Jira permission scheme");
    Set<String> users = new LinkedHashSet<>();
    Set<IssueGrant> dynamic = new LinkedHashSet<>();
    Set<String> holderTypes = new HashSet<>();
    for (JsonNode permission : array(scheme, "permissions")) {
      if (text(permission, "permission").equals("BROWSE_PROJECTS")) {
        JsonNode holder = permission.path("holder");
        String type = text(holder, "type");
        holderTypes.add(type);
        if (Set.of("reporter", "assignee", "userCustomField", "groupCustomField").contains(type)) {
          String field = type.endsWith("CustomField") ? parameter(holder) : "";
          dynamic.add(new IssueGrant(type, field));
        } else users.addAll(holderUsers(projectId, project, holder));
      }
    }
    identities.values().stream()
        .filter(value -> value.guest() || value.deleted())
        .forEach(value -> users.remove(value.sourceUserId()));
    return new Browse(
        project,
        Set.copyOf(users),
        Set.copyOf(dynamic),
        !holderTypes.isEmpty() && Collections.disjoint(holderTypes, SUPPORTED_HOLDERS));
  }

  private Set<String> holderUsers(String projectId, JsonNode project, JsonNode holder) {
    return switch (text(holder, "type")) {
      case "user" -> Set.of(user(parameter(holder)));
      case "projectLead" -> Set.of(user(text(project.path("lead"), "accountId")));
      case "projectRole" -> roleUsers(projectId, parameter(holder));
      case "group" -> groupUsers(holder);
      default -> Set.of();
    };
  }

  private String parameter(JsonNode holder) {
    // value는 변경 가능한 그룹 이름과 달리 불변 groupId다. 다른 holder의 parameter는 원천 식별자다.
    return holder.has("value") ? text(holder, "value") : text(holder, "parameter");
  }

  private Set<String> roleUsers(String projectId, String roleId) {
    JsonNode role = http.read("/project/" + segment(projectId) + "/role/" + segment(roleId));
    if (!id(role, "id").equals(roleId)) throw incomplete("Conflicting Jira role id");
    Set<String> users = new LinkedHashSet<>();
    for (JsonNode actor : array(role, "actors")) {
      switch (text(actor, "type")) {
        case "atlassian-user-role-actor" ->
            users.add(user(text(actor.path("actorUser"), "accountId")));
        case "atlassian-group-role-actor" -> users.addAll(groupUsers(actor.path("actorGroup")));
        default -> throw incomplete("Unknown Jira role actor");
      }
    }
    return Set.copyOf(users);
  }

  private Set<String> groupUsers(JsonNode holder) {
    boolean hasId = holder.has("groupId") || holder.has("value");
    String group = holder.has("groupId") ? text(holder, "groupId") : parameter(holder);
    if (group.startsWith("~")) throw incomplete("Reserved Jira source group id");
    String selector = hasId ? "groupId=" : "groupname=";
    String cacheKey = selector + group;
    GroupSnapshot cached = groups.get(cacheKey);
    if (cached != null) return cached.members();
    Set<String> users = new LinkedHashSet<>();
    http.visit(
        "/group/member?" + selector + segment(group) + "&includeInactiveUsers=true",
        member -> {
          String account = identity(member);
          if (!users.add(account)) throw incomplete("Duplicate Jira group member");
        });
    // 옛 holder의 변경 가능한 이름과 불변 groupId는 다른 식별 공간이다.
    String sourceGroup = hasId ? group : "name:" + group;
    GroupSnapshot snapshot = new GroupSnapshot(sourceGroup, users, clock.instant());
    groups.put(cacheKey, snapshot);
    return snapshot.members();
  }

  private String user(String account) {
    if (!identities.containsKey(account)) {
      JsonNode body = http.read("/user?accountId=" + segment(account));
      if (!text(body, "accountId").equals(account)) throw incomplete("Conflicting Jira accountId");
      identity(body);
    }
    return account;
  }

  private String identity(JsonNode body) {
    String account = text(body, "accountId");
    if (account.startsWith("~") || !StandardCharsets.UTF_8.newEncoder().canEncode(account))
      throw incomplete("Invalid Jira accountId");
    String type = text(body, "accountType");
    if (!Set.of("atlassian", "customer", "app").contains(type) || !body.path("active").isBoolean())
      throw incomplete("Unknown Jira account state");
    Optional<String> email = Optional.empty();
    if (body.hasNonNull("emailAddress")) email = Optional.of(text(body, "emailAddress"));
    SourceIdentityRecord value =
        new SourceIdentityRecord(
            account,
            email,
            email.map(e -> input.domains().admits(e, input.source().id())).orElse(false),
            !type.equals("atlassian"),
            !body.path("active").asBoolean(),
            clock.instant());
    SourceIdentityRecord old = identities.put(account, value);
    if (old != null && !sameIdentity(old, value)) {
      conflictingIdentities.add(old);
      throw incomplete("Conflicting Jira identity observations");
    }
    return account;
  }

  private boolean sameIdentity(SourceIdentityRecord first, SourceIdentityRecord second) {
    return first.email().equals(second.email())
        && first.emailVerified() == second.emailVerified()
        && first.guest() == second.guest()
        && first.deleted() == second.deleted();
  }

  private Map<String, Grant> levels(String projectId) {
    List<JsonNode> mappings =
        http.list("/issuesecurityschemes/project?projectId=" + segment(projectId));
    if (mappings.isEmpty()) return Map.of();
    if (mappings.size() != 1 || !text(mappings.getFirst(), "projectId").equals(projectId))
      throw incomplete("Conflicting Jira security scheme mapping");
    String scheme = text(mappings.getFirst(), "issueSecuritySchemeId");
    Set<String> applied = appliedProjects(scheme);
    if (!applied.contains(projectId)) throw incomplete("Missing Jira security scheme project");
    Map<String, Grant> levels = new LinkedHashMap<>();
    for (JsonNode level : http.list("/issuesecurityschemes/level?schemeId=" + segment(scheme))) {
      if (!text(level, "issueSecuritySchemeId").equals(scheme))
        throw incomplete("Conflicting Jira level scheme");
      String levelId = id(level, "id");
      Set<String> finalUsers = sharedLevel(scheme, levelId, applied);
      if (levels.putIfAbsent(levelId, grants.level(levelId, finalUsers, clock.instant())) != null)
        throw incomplete("Duplicate Jira security level");
    }
    return Map.copyOf(levels);
  }

  private Set<String> appliedProjects(String scheme) {
    Set<String> applied = new LinkedHashSet<>();
    for (JsonNode mapping :
        http.list("/issuesecurityschemes/project?issueSecuritySchemeId=" + segment(scheme))) {
      if (!text(mapping, "issueSecuritySchemeId").equals(scheme)
          || !applied.add(text(mapping, "projectId")))
        throw incomplete("Conflicting Jira applied project");
    }
    return Set.copyOf(applied);
  }

  private Set<String> sharedLevel(String scheme, String level, Set<String> applied) {
    List<JsonNode> members =
        http.list("/issuesecurityschemes/level/member?levelId=" + segment(level));
    Set<String> intersection = new LinkedHashSet<>();
    boolean first = true;
    for (String projectId : applied) {
      Browse browse = browse(projectId);
      Set<String> users = levelUsers(projectId, browse, scheme, level, members);
      if (first) intersection.addAll(users);
      else intersection.retainAll(users);
      first = false;
    }
    return Set.copyOf(intersection);
  }

  private Set<String> levelUsers(
      String projectId, Browse browse, String scheme, String level, List<JsonNode> members) {
    Set<String> users = new LinkedHashSet<>();
    Set<String> memberIds = new HashSet<>();
    for (JsonNode member : members) {
      if (!text(member, "issueSecuritySchemeId").equals(scheme)
          || !text(member, "issueSecurityLevelId").equals(level)
          || !memberIds.add(id(member, "id"))) throw incomplete("Conflicting Jira level member");
      users.addAll(holderUsers(projectId, browse.project(), member.path("holder")));
    }
    users.retainAll(browse.users());
    return users;
  }
}
