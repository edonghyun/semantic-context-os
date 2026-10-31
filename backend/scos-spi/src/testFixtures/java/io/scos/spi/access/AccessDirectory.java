package io.scos.spi.access;

import io.scos.spi.connector.SourceInstanceId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 계약·종단 테스트가 저장소별 시드 훅에 넘기는 불변 접근 디렉터리. */
public record AccessDirectory(
    List<User> users,
    List<Identity> identities,
    List<Link> links,
    List<Group> groups,
    Set<SourceInstanceId> scopes) {
  public AccessDirectory {
    users = List.copyOf(users);
    identities = List.copyOf(identities);
    links = List.copyOf(links);
    groups = List.copyOf(groups);
    scopes = Set.copyOf(scopes);
  }

  public static AccessDirectory empty() {
    return new AccessDirectory(List.of(), List.of(), List.of(), List.of(), Set.of());
  }

  public AccessDirectory withUser(User user) {
    return new AccessDirectory(append(users, user), identities, links, groups, scopes);
  }

  public AccessDirectory withIdentity(Identity identity) {
    return new AccessDirectory(users, append(identities, identity), links, groups, scopes);
  }

  public AccessDirectory withLink(Link link) {
    return new AccessDirectory(users, identities, append(links, link), groups, scopes);
  }

  public AccessDirectory withGroup(Group group) {
    return new AccessDirectory(users, identities, links, append(groups, group), scopes);
  }

  public AccessDirectory withScope(SourceInstanceId scope) {
    var designated = new HashSet<>(scopes);
    designated.add(scope);
    return new AccessDirectory(users, identities, links, groups, designated);
  }

  private static <T> List<T> append(List<T> values, T value) {
    var result = new ArrayList<>(values);
    result.add(value);
    return result;
  }

  public record User(String id, String email, boolean active) {}

  public record Identity(SourceInstanceId instance, String id, boolean guest, boolean deleted) {}

  public record Link(SourceInstanceId instance, String id, String userId, String method) {}

  public record Group(SourceInstanceId instance, String id, Set<String> members) {
    public Group {
      members = Set.copyOf(members);
    }
  }
}
