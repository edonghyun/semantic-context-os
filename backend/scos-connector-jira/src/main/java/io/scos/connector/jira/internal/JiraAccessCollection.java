package io.scos.connector.jira.internal;

import static io.scos.connector.jira.internal.JiraAccessResponses.id;
import static io.scos.connector.jira.internal.JiraAccessResponses.incomplete;
import static io.scos.connector.jira.internal.JiraApiClient.segment;

import io.scos.connector.jira.internal.JiraAccessDirectory.Decision;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RawAcl;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 매 run의 저장 carrier를 다시 관측한다. 대상 실패를 보낸 뒤 독립 대상을 계속하고 마지막에 전체성을 판정한다. */
public final class JiraAccessCollection {
  private static final String ISSUE_FIELDS = "project,security,reporter,assignee";
  private final CredentialResolver credentials;
  private final JiraApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public JiraAccessCollection(
      CredentialResolver credentials, JiraApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  public void collect(AccessInput input, AclSink sink) {
    var config = JiraConfiguration.read(json, input.source().config());
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    new Attempt(input, config, token).collect(sink);
  }

  private final class Attempt {
    private final AccessInput input;
    private final JiraAccessResponses http;
    private final JiraAccessDirectory directory;

    Attempt(AccessInput input, JiraConfiguration config, String token) {
      this.input = input;
      http = new JiraAccessResponses(input, config, token, client);
      directory = new JiraAccessDirectory(input, config, token, client, clock);
    }

    void collect(AclSink sink) {
      Optional<CallFailureException> firstFailure = Optional.empty();
      for (String target : input.externalIds()) {
        requireActive();
        AclPage page;
        try {
          page = observe(target);
        } catch (CallFailureException failure) {
          if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
          if (firstFailure.isEmpty()) firstFailure = Optional.of(failure);
          page = aclPage(target, Set.of(), DocumentAcl.Status.FAILED);
        }
        sink.accept(page);
      }
      requireActive();
      directory.requireComplete();
      if (firstFailure.isPresent()) throw firstFailure.orElseThrow();
    }

    private AclPage observe(String target) {
      int separator = target.indexOf(':');
      if (separator < 1 || separator == target.length() - 1)
        throw incomplete("Invalid Jira carrier");
      String kind = target.substring(0, separator);
      String targetId = target.substring(separator + 1);
      return switch (kind) {
        case "issue" -> issue(target, targetId);
        case "project" -> artifact(target, targetId);
        case "component", "version" -> {
          JsonNode details = http.read("/" + kind + "/" + segment(targetId));
          if (!id(details, "id").equals(targetId)) throw incomplete("Conflicting Jira artifact id");
          yield artifact(target, id(details, "projectId"));
        }
        case "filter", "dashboard", "board", "sprint" ->
            aclPage(target, Set.of(), DocumentAcl.Status.FAILED);
        default -> throw incomplete("Unknown Jira carrier");
      };
    }

    private AclPage artifact(String target, String projectId) {
      var project = directory.readProject(projectId);
      if (project.decision() != Decision.SUPPORTED)
        return aclPage(target, Set.of(), DocumentAcl.Status.FAILED);
      return aclPage(
          target, Set.of(project.projectGrant().orElseThrow().key()), DocumentAcl.Status.OK);
    }

    private AclPage issue(String target, String issueId) {
      if (storedProjectIsBlocked(target))
        return aclPage(target, Set.of(), DocumentAcl.Status.FAILED);
      JsonNode issue = readIssue(issueId, ISSUE_FIELDS);
      String projectId = id(issue.path("fields").path("project"), "id");
      var project = directory.readProject(projectId);
      if (project.decision() != Decision.SUPPORTED)
        return aclPage(target, Set.of(), DocumentAcl.Status.FAILED);
      String fields = directory.issueFields(project);
      if (!fields.isEmpty()) {
        issue = readIssue(issueId, ISSUE_FIELDS + "," + fields);
        if (!id(issue.path("fields").path("project"), "id").equals(projectId))
          throw incomplete("Conflicting Jira issue project");
      }
      return aclPage(target, directory.issueKeys(project, issue), DocumentAcl.Status.OK);
    }

    private boolean storedProjectIsBlocked(String target) {
      Optional<String> stored = input.storedContent().apply(target);
      if (stored.isEmpty()) return false;
      try {
        String projectId =
            id(json.readTree(stored.orElseThrow()).path("fields").path("project"), "id");
        return directory.readProject(projectId).decision() == Decision.POLICY_BLOCKED;
      } catch (JacksonException invalid) {
        throw incomplete("Malformed Jira stored issue JSON");
      }
    }

    private JsonNode readIssue(String issueId, String fields) {
      JsonNode issue =
          http.read("/issue/" + segment(issueId) + "?fields=" + fields + "&expand=schema");
      if (!id(issue, "id").equals(issueId)) throw incomplete("Conflicting Jira issue id");
      return issue;
    }

    private AclPage aclPage(String target, Set<AccessKey> keys, DocumentAcl.Status status) {
      AclPage observations = directory.observations();
      return new AclPage(
          Map.of(target, RawAcl.observed(status, keys, clock.instant())),
          observations.groups(),
          observations.identities());
    }

    private void requireActive() {
      if (input.cancel().isCancelled() || Thread.currentThread().isInterrupted())
        throw new CallFailureException(
            "jira.access", CallFailure.of(CallFailure.Kind.CANCELLED, "Jira access cancelled"));
    }
  }
}
