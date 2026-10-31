package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 문서 ACL 저장소의 구현 공통 계약. */
@DisplayName("ACL 저장소는 공간 안에서 최신 변경만 보존한다")
public abstract class AclStoreContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");
  protected static final Instant OBSERVED = Instant.parse("2026-10-26T00:00:00Z");
  protected static final AccessKey USER = AccessKey.parse("user:slack-a:U1");
  protected AclStore store;

  protected abstract AclStore newStore();

  @BeforeEach
  void createStore() {
    store = newStore();
  }

  @Test
  @DisplayName("비어 있으면 ACL 행이 없다고 알려준다")
  void emptyStoreHasNoAcl() {
    boolean any = store.hasAny();

    // then
    assertThat(any).isFalse();
  }

  @Test
  @DisplayName("다른 공간의 거부 상태 ACL도 존재하는 행으로 센다")
  void anySpaceAndStatusHasAcl() {
    store.put(B, List.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED)));

    boolean any = store.hasAny();

    // then
    assertThat(any).isTrue();
  }

  @Test
  @DisplayName("넣은 ACL을 그대로 돌려준다")
  void roundTrip() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(acl));

    var found = store.get(A, List.of("doc"));

    // then
    assertThat(found).containsOnlyKeys("doc").containsEntry("doc", acl);
  }

  @Test
  @DisplayName("다른 공간의 같은 문서 id는 섞이지 않는다")
  void spacesAreIsolated() {
    var first = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    var second = new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED);
    store.put(A, List.of(first));
    store.put(B, List.of(second));

    var found = store.get(A, List.of("doc"));

    // then
    assertThat(found).containsEntry("doc", first);
  }

  @Test
  @DisplayName("같은 fingerprint는 observedAt을 바꾸지 않는다")
  void identicalFingerprintKeepsObservation() {
    var first = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(first));

    store.put(
        A, List.of(new DocumentAcl("doc", first.status(), first.keys(), OBSERVED.plusSeconds(1))));

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", first);
  }

  @Test
  @DisplayName("더 오래된 observedAt의 쓰기는 무시한다")
  void olderObservationIsIgnored() {
    var first = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(first));

    store.put(
        A,
        List.of(
            new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED.minusSeconds(1))));

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", first);
  }

  @Test
  @DisplayName("지운 문서는 돌려주지 않는다")
  void deletedDocumentIsAbsent() {
    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));

    store.delete(A, List.of("doc"));

    // then
    assertThat(store.get(A, List.of("doc"))).isEmpty();
  }

  @Test
  @DisplayName("더 새 observedAt의 다른 ACL은 덮어쓴다")
  void newerChangeReplacesAcl() {
    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));
    var newer =
        new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED.plusSeconds(1));

    store.put(A, List.of(newer));

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", newer);
  }

  @Test
  @DisplayName("같은 observedAt의 다른 ACL은 덮어쓰지 않는다")
  void equalObservationIsIgnored() {
    var first = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(first));

    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(), OBSERVED)));

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", first);
  }

  @Test
  @DisplayName("없는 문서는 결과에서 빠진다")
  void missingDocumentIsAbsent() {
    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));

    var found = store.get(A, List.of("missing"));

    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("빈 키 배열의 OK도 저장한다")
  void emptyKeysAreStored() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(), OBSERVED);

    store.put(A, List.of(acl));

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", acl);
  }

  @Test
  @DisplayName("삭제는 다른 공간의 같은 문서를 보존한다")
  void deletePreservesOtherSpace() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(acl));
    store.put(B, List.of(acl));

    store.delete(A, List.of("doc"));

    // then
    assertThat(store.get(B, List.of("doc"))).containsEntry("doc", acl);
  }

  @Test
  @DisplayName("삭제는 요청하지 않은 문서를 보존한다")
  void deletePreservesOtherDocument() {
    var kept = new DocumentAcl("kept", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(
        A,
        List.of(kept, new DocumentAcl("deleted", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));

    store.delete(A, List.of("deleted", "missing"));

    // then
    assertThat(store.get(A, List.of("kept", "deleted")))
        .containsOnlyKeys("kept")
        .containsEntry("kept", kept);
  }

  @Test
  @DisplayName("빈 쓰기는 저장한 ACL을 보존한다")
  void emptyPutChangesNothing() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(acl));

    store.put(A, List.of());

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", acl);
  }

  @Test
  @DisplayName("빈 조회는 빈 결과다")
  void emptyGetReturnsNothing() {
    store.put(A, List.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));

    var found = store.get(A, List.of());

    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("빈 삭제는 저장한 ACL을 보존한다")
  void emptyDeleteChangesNothing() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    store.put(A, List.of(acl));

    store.delete(A, List.of());

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", acl);
  }

  @ParameterizedTest
  @EnumSource(DocumentAcl.Status.class)
  @DisplayName("모든 관측 상태를 그대로 저장한다")
  void statusesRoundTrip(DocumentAcl.Status status) {
    var acl = new DocumentAcl("doc", status, Set.of(AccessKey.parse("scope:slack-a:*")), OBSERVED);

    store.put(A, List.of(acl));

    // then
    assertThat(store.get(A, List.of("doc"))).containsEntry("doc", acl);
  }
}
