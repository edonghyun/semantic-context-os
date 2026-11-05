package io.scos.server;

import io.scos.collection.internal.FixedSourceQueries;
import io.scos.collection.internal.SourceQueries.AccessExplanation;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryIdentityStore.Directory;
import io.scos.store.memory.MemoryIdentityStore.Identity;
import io.scos.store.memory.MemoryIdentityStore.Link;
import io.scos.store.memory.MemoryIdentityStore.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 실제 메모리 신원 포트와 읽기 전용 문서 조회 응답을 연결한다. */
final class AccessExplainerScenario {
  private static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final AccessKey KEY = AccessKey.parse("user:drive-a:reader@example.com");
  private final MemoryIdentityStore identities = new MemoryIdentityStore();
  private Optional<DocumentAcl> acl =
      Optional.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(KEY), NOW));
  private boolean known = true;

  AccessExplainerScenario() {
    identities.replace(
        new Directory(
            List.of(new User("reader", "reader@example.com", true)),
            List.of(new Identity(SOURCE, "reader@example.com", false, false)),
            List.of(new Link(SOURCE, "reader@example.com", "reader")),
            List.of(),
            Set.of()));
  }

  void missingAcl() {
    acl = Optional.empty();
  }

  void unknownDocument() {
    known = false;
  }

  void failedWithKeys() {
    acl = Optional.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(KEY), NOW));
  }

  void disjointKeys() {
    acl =
        Optional.of(
            new DocumentAcl(
                "doc",
                DocumentAcl.Status.OK,
                Set.of(AccessKey.parse("user:drive-a:other@example.com")),
                NOW));
  }

  Optional<AccessExplainer.Result> explain(Optional<String> user) {
    var document = new AccessExplanation("doc", SOURCE, SpaceId.LEGACY, acl, List.of());
    var queries =
        new FixedSourceQueries(List.of(), Map.of(), known ? Map.of("doc", document) : Map.of());
    return new AccessExplainer(queries, identities, new DomainPolicy(Set.of(), Map.of()))
        .explain("doc", user);
  }
}
