package io.scos.store.memory;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.source.SourceInstanceId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 원천 신원·연결·그룹의 한 스냅샷으로 접근 키를 계산한다. */
public final class MemoryIdentityStore implements IdentityStore {
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
            current.users(), nextIdentities, current.links(), nextGroups, current.scopes());
  }

  boolean containsUser(String userId) {
    return directory.users().stream().anyMatch(user -> user.id().equals(userId));
  }

  @Override
  public Optional<Set<AccessKey>> accessKeysOf(String userId, DomainPolicy domains) {
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
    for (SourceInstanceId instance : snapshot.scopes())
      if (instances.admits(instance, logger))
        keys.add(new AccessKey(AccessKey.Kind.SCOPE, instance, "*"));
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
      Set<SourceInstanceId> scopes) {
    public Directory {
      users = List.copyOf(users);
      identities = List.copyOf(identities);
      links = List.copyOf(links);
      groups = List.copyOf(groups);
      scopes = Set.copyOf(scopes);
    }
  }

  public record User(String id, String email, boolean active) {}

  public record Identity(SourceInstanceId instance, String id, boolean guest, boolean deleted) {}

  public record Link(SourceInstanceId instance, String id, String userId) {}

  public record Group(SourceInstanceId instance, String id, Set<String> members) {
    public Group {
      members = Set.copyOf(members);
    }
  }
}
