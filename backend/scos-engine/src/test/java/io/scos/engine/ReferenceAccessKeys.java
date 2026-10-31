package io.scos.engine;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.Group;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DomainPolicy;
import java.util.HashSet;
import java.util.Set;

/** 설계 §4.2를 신원별로 해석하는 작은 오라클. 저장소의 키 계산을 호출하지 않는다. */
final class ReferenceAccessKeys {
  Set<AccessKey> keys(AccessDirectory directory, User user, DomainPolicy domains) {
    var keys = new HashSet<AccessKey>();
    if (!user.active()) return Set.of();
    for (var link : directory.links()) {
      if (!link.userId().equals(user.id())) continue;
      for (var identity : directory.identities()) {
        if (!identity.instance().equals(link.instance()) || !identity.id().equals(link.id()))
          continue;
        if (identity.guest() || identity.deleted()) continue;
        keys.add(new AccessKey(AccessKey.Kind.USER, identity.instance(), identity.id()));
        for (var group : directory.groups()) {
          if (contains(directory, group, identity))
            keys.add(new AccessKey(AccessKey.Kind.GROUP, group.instance(), group.id()));
        }
      }
    }
    for (var instance : directory.scopes())
      keys.add(new AccessKey(AccessKey.Kind.SCOPE, instance, "*"));
    String domain = user.email().substring(user.email().lastIndexOf('@') + 1);
    for (var entry : domains.instanceDomains().entrySet()) {
      if (domain.equals(entry.getValue()) && domains.orgDomains().contains(domain))
        keys.add(new AccessKey(AccessKey.Kind.DOMAIN, entry.getKey(), domain));
    }
    return Set.copyOf(keys);
  }

  private boolean contains(AccessDirectory directory, Group group, Identity identity) {
    if (!group.instance().equals(identity.instance())) return false;
    if (group.members().contains(identity.id())) return true;
    if (group.id().startsWith("~")) return false;
    for (var nested : directory.groups()) {
      if (nested.id().startsWith("~")
          && nested.instance().equals(group.instance())
          && group.members().contains(nested.id())
          && nested.members().contains(identity.id())) return true;
    }
    return false;
  }
}
