package io.scos.server;

import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.AccessExplanation;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.IdentityStore;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 저장된 결정과 실제 읽기 경로의 사용자 키를 대조한다. 관측·연결·소유를 갱신하지 않는다. */
final class AccessExplainer {
  private final SourceQueries queries;
  private final IdentityStore identities;
  private final DomainPolicy domains;

  record UserAccess(
      String id, boolean known, Set<AccessKey> keys, Set<AccessKey> intersection, boolean visible) {
    UserAccess {
      keys = Set.copyOf(keys);
      intersection = Set.copyOf(intersection);
    }
  }

  record Result(AccessExplanation document, Optional<UserAccess> user) {}

  AccessExplainer(SourceQueries queries, IdentityStore identities, DomainPolicy domains) {
    this.queries = queries;
    this.identities = identities;
    this.domains = domains;
  }

  Optional<Result> explain(String document, Optional<String> user) {
    return queries
        .accessExplanation(document)
        .map(explanation -> new Result(explanation, user.map(id -> resolve(id, explanation))));
  }

  private UserAccess resolve(String user, AccessExplanation document) {
    var resolved = identities.accessKeysOf(user, domains);
    Set<AccessKey> keys = resolved.orElse(Set.of());
    var intersection = new HashSet<>(keys);
    intersection.retainAll(document.acl().map(acl -> acl.keys()).orElse(Set.of()));
    boolean visible = document.acl().map(acl -> acl.visibleTo(keys)).orElse(false);
    return new UserAccess(user, resolved.isPresent(), keys, intersection, visible);
  }
}
