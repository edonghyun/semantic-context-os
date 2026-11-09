package io.scos.store.memory;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 원천 신원·연결·그룹의 한 스냅샷으로 접근 키를 계산한다. */
public final class MemoryIdentityStore implements IdentityStore {
  private final Map<SourceInstanceId, SourceAccessDirectory.Snapshot> sourceAccess =
      new HashMap<>();

  private final AccessKeyInstances instances = new AccessKeyInstances();
  private final System.Logger logger = System.getLogger(MemoryIdentityStore.class.getName());
  private volatile Directory directory =
      new Directory(List.of(), List.of(), List.of(), List.of(), Set.of());

  public synchronized void replace(Directory snapshot) {
    directory = snapshot;
  }

  /** 원천 접근 상태만 원자적으로 교체한다. 사용자·연결·조직 지정과 다른 원천은 유지한다. */
  public synchronized void replaceSourceAccess(
      SourceInstanceId source, List<Identity> identities, List<Group> groups) {
    if (identities.stream().anyMatch(i -> !i.instance().equals(source))
        || groups.stream().anyMatch(g -> !g.instance().equals(source)))
      throw new IllegalArgumentException("Source access must belong to the replaced source");
    Directory current = directory;
    var nextIdentities =
        new ArrayList<>(
            current.identities().stream().filter(i -> !i.instance().equals(source)).toList());
    nextIdentities.addAll(identities);
    var nextGroups =
        new ArrayList<>(
            current.groups().stream().filter(g -> !g.instance().equals(source)).toList());
    nextGroups.addAll(groups);
    directory =
        new Directory(
            current.users(),
            nextIdentities,
            current.links(),
            nextGroups,
            current.scopes(),
            current.sources());
  }

  /** 사용자·토큰·수집 디렉터리의 게시와 관리 트랜잭션이 공유하는 재진입 경계. */
  public synchronized <T> T withDirectoryLock(Supplier<T> work) {
    return work.get();
  }

  public synchronized void withDirectoryLock(Runnable work) {
    work.run();
  }

  /** 같은 신원 상태에 연결된 디렉터리는 확정 관측 원장도 공유한다. 접근은 공동 잠금 안에서만 한다. */
  Map<SourceInstanceId, SourceAccessDirectory.Snapshot> sourceAccessSnapshots() {
    return sourceAccess;
  }

  synchronized Directory snapshot() {
    return directory;
  }

  public synchronized void registerSource(SourceAccount source) {
    var sources = new ArrayList<>(directory.sources());
    sources.removeIf(existing -> existing.id().equals(source.id()));
    sources.add(source);
    directory =
        new Directory(
            directory.users(),
            directory.identities(),
            directory.links(),
            directory.groups(),
            directory.scopes(),
            sources);
  }

  synchronized boolean containsUser(String userId) {
    return directory.users().stream().anyMatch(user -> user.id().equals(userId));
  }

  @Override
  public synchronized Optional<Set<AccessKey>> accessKeysOf(String userId, DomainPolicy domains) {
    Directory snapshot = directory;
    return snapshot.users().stream()
        .filter(u -> u.id().equals(userId) && u.active())
        .findFirst()
        .map(u -> keysOf(u, snapshot, domains));
  }

  private Set<AccessKey> keysOf(User user, Directory snapshot, DomainPolicy domains) {
    var identities =
        identitiesOf(user, snapshot).stream()
            .filter(i -> instances.admits(i.instance(), logger))
            .toList();
    var keys =
        identities.stream()
            .map(i -> new AccessKey(AccessKey.Kind.USER, i.instance(), i.id()))
            .collect(Collectors.toCollection(HashSet::new));
    var direct = directGroupsOf(identities, snapshot.groups());
    for (Group group : direct)
      keys.add(new AccessKey(AccessKey.Kind.GROUP, group.instance(), group.id()));
    for (Group group : referringGroupsOf(direct, snapshot.groups()))
      keys.add(new AccessKey(AccessKey.Kind.GROUP, group.instance(), group.id()));
    for (ScopeDesignation scope : snapshot.scopes()) {
      SourceInstanceId instance = scope.source();
      if (instances.admits(instance, logger))
        keys.add(new AccessKey(AccessKey.Kind.SCOPE, instance, "*"));
    }
    domains
        .instanceDomains()
        .forEach(
            (instance, domain) -> {
              if (instances.admits(instance, logger) && domains.admits(user.email(), instance))
                keys.add(new AccessKey(AccessKey.Kind.DOMAIN, instance, domain));
            });
    return Set.copyOf(keys);
  }

  private List<Group> directGroupsOf(List<Identity> identities, List<Group> groups) {
    return groups.stream()
        .filter(
            g ->
                identities.stream()
                    .anyMatch(
                        i -> i.instance().equals(g.instance()) && g.members().contains(i.id())))
        .toList();
  }

  private List<Group> referringGroupsOf(List<Group> direct, List<Group> groups) {
    return groups.stream()
        .filter(group -> !group.id().startsWith("~"))
        .filter(
            group ->
                direct.stream()
                    .anyMatch(
                        g ->
                            g.id().startsWith("~")
                                && g.instance().equals(group.instance())
                                && group.members().contains(g.id())))
        .toList();
  }

  private List<Identity> identitiesOf(User user, Directory snapshot) {
    return snapshot.identities().stream()
        .filter(i -> !i.guest() && !i.deleted())
        .filter(
            i ->
                snapshot.links().stream()
                    .anyMatch(
                        l ->
                            l.userId().equals(user.id())
                                && l.instance().equals(i.instance())
                                && l.id().equals(i.id())))
        .toList();
  }

  public record Directory(
      List<User> users,
      List<Identity> identities,
      List<Link> links,
      List<Group> groups,
      List<ScopeDesignation> scopes,
      List<SourceAccount> sources) {
    public Directory {
      users = List.copyOf(users);
      identities = List.copyOf(identities);
      links = List.copyOf(links);
      groups = List.copyOf(groups);
      scopes = List.copyOf(scopes);
      sources = List.copyOf(sources);
    }

    public Directory(
        List<User> users,
        List<Identity> identities,
        List<Link> links,
        List<Group> groups,
        Set<SourceInstanceId> scopes) {
      this(
          users,
          identities,
          links,
          groups,
          scopes.stream()
              .map(source -> new ScopeDesignation(source, "fixture", Instant.EPOCH))
              .toList(),
          List.of());
    }
  }

  public record User(String id, String email, boolean active, Role role, Instant createdAt) {
    public User(String id, String email, boolean active) {
      this(id, email, active, Role.MEMBER, Instant.EPOCH);
    }
  }

  public record Identity(SourceInstanceId instance, String id, boolean guest, boolean deleted) {}

  public record Link(
      SourceInstanceId instance,
      String id,
      String userId,
      LinkMethod method,
      Instant linkedAt,
      String linkedBy) {
    public Link(SourceInstanceId instance, String id, String userId) {
      this(instance, id, userId, LinkMethod.ADMIN, Instant.EPOCH, "fixture");
    }
  }

  public record Group(SourceInstanceId instance, String id, Set<String> members) {
    public Group {
      members = Set.copyOf(members);
    }
  }
}
