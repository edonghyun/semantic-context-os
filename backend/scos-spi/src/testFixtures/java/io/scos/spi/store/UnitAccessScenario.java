package io.scos.spi.store;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 유닛 읽기 계약의 문서·링크와 공간별 ACL 준비를 소유한다. */
final class UnitAccessScenario {
  private static final AccessKey ALICE_KEY = AccessKey.parse("user:slack-a:U1");
  private static final AccessKey BOB_KEY = AccessKey.parse("user:slack-a:U2");
  private static final AccessKey SCOPE_KEY = AccessKey.parse("scope:notion-a:*");
  private static final Instant OBSERVED = Instant.parse("2026-10-25T00:00:00Z");
  private static final int UNIT_COUNT = 7;
  static final AccessScope ALICE =
      new AccessScope.Restricted(new Principal("alice"), Set.of(ALICE_KEY));
  static final AccessScope ORG =
      new AccessScope.Restricted(new Principal("alice"), Set.of(SCOPE_KEY));
  static final AccessScope NO_KEYS = new AccessScope.Restricted(new Principal("alice"), Set.of());
  static final List<String> IDS = List.of("u6", "u1b", "u2", "u3", "u4", "u5", "u1", "u1b");
  static final List<String> DOCUMENTS = List.of("d6", "d1", "d2", "d3", "d4", "d5");
  private final UnitStore store;
  private final UnitMaintenance maintenance;
  private final AclStore acls;
  private final SpaceId space;

  UnitAccessScenario(UnitStore store, UnitMaintenance maintenance, AclStore acls, SpaceId space) {
    this.store = store;
    this.maintenance = maintenance;
    this.acls = acls;
    this.space = space;
  }

  void givenDocuments() {
    maintenance.upsert(
        space,
        List.of(
            ContextUnits.unit("u1")
                .inDocument("d1")
                .linksTo("d1", "d2", "d3", "d4", "d5", "d6")
                .build(),
            ContextUnits.unit("u1b").inDocument("d1").build(),
            ContextUnits.unit("u2").inDocument("d2").build(),
            ContextUnits.unit("u3").inDocument("d3").build(),
            ContextUnits.unit("u4").inDocument("d4").build(),
            ContextUnits.unit("u5").inDocument("d5").build(),
            ContextUnits.unit("u6").inDocument("d6").build()));
    acls.put(
        space,
        List.of(
            acl("d1", Status.OK, ALICE_KEY),
            acl("d2", Status.OK, BOB_KEY),
            acl("d3", Status.UNKNOWN, ALICE_KEY),
            acl("d4", Status.FAILED, ALICE_KEY),
            acl("d5", Status.UNSUPPORTED, SCOPE_KEY)));
  }

  void requireReadableDocuments() {
    if (store.getMany(space, AccessScope.singleUser(), IDS).stream()
            .map(ContextUnit::id)
            .distinct()
            .count()
        != UNIT_COUNT) throw new IllegalStateException("given 준비 실패: 일곱 유닛을 모두 읽을 수 있어야 한다");
  }

  void givenMixedTiers() {
    maintenance.upsert(
        space,
        List.of(
            ContextUnits.unit("summary-first").inDocument("mixed").compacted("original").build(),
            ContextUnits.unit("raw-first").inDocument("mixed").build(),
            ContextUnits.unit("summary-middle").inDocument("mixed").compacted("original").build(),
            ContextUnits.unit("raw-second").inDocument("mixed").build(),
            ContextUnits.unit("raw-third").inDocument("mixed").build()));
    acls.put(space, List.of(acl("mixed", Status.OK, ALICE_KEY)));
  }

  void givenAclOnlyInOtherSpace(SpaceId other) {
    acls.delete(space, List.of("d1"));
    acls.put(other, List.of(acl("d1", Status.OK, ALICE_KEY)));
  }

  private DocumentAcl acl(String id, Status status, AccessKey key) {
    return new DocumentAcl(id, status, Set.of(key), OBSERVED);
  }
}
