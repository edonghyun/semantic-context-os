package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.collection.internal.RawItemStore.Outcome;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 원본 저장 포트 계약(F4 mutation 의미, F5 reconcile, F8 blob, G1 active 목록). */
@DisplayName("원본 저장소는 mutation·reconcile·blob 의미를 지킨다(F4·F5·F8·G1)")
public abstract class RawItemStoreContract {
  protected static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  protected static final SourceInstanceId OTHER = new SourceInstanceId("src-b");
  protected static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Instant OCCURRED = T0.plusSeconds(10);
  static final Instant OBSERVED = T0.plusSeconds(20);
  static final Instant RECONCILED = T0.plusSeconds(60);
  static final int PAGE = 2;

  /** 64KiB 인라인 상한을 넘는 콘텐츠. */
  static final String BIG_JSON = "{\"text\":\"" + "x".repeat(70_000) + "\"}";

  static final String MISSING_SHA = "0".repeat(64);

  protected RawItemStore store;

  protected abstract RawItemStore newStore();

  @BeforeEach
  void setUpContract() {
    store = newStore();
  }

  static SourceOrder order(String position) {
    return new SourceOrder("slack.message-state.v1", position);
  }

  /**
   * 기본: slack.message, metadata{channelId, messageTs}, inline 본문 {"text": version}. position이
   * null이면 순서 없음.
   */
  static RawItem item(String id, String version, String position) {
    RawItems b =
        RawItems.item(id)
            .version(version)
            .metadata("{\"channelId\":\"C1\",\"messageTs\":\"" + id + "\"}")
            .inlineJson("{\"text\":\"" + version + "\"}");
    return (position == null ? b : b.at(position)).build();
  }

  Outcome upsert(String id, String version, String position) {
    return store.apply(SOURCE, new RawMutation.Upsert(item(id, version, position)));
  }

  Outcome delete(
      String id,
      Optional<String> version,
      Optional<SourceOrder> order,
      Optional<Instant> occurred,
      Instant observed) {
    return store.apply(SOURCE, new RawMutation.Delete(id, version, order, occurred, observed));
  }

  RawItem active(String id) {
    return ((RawRecord.Active) store.get(SOURCE, id).orElseThrow()).item();
  }

  RawRecord.Deleted tombstone(String id) {
    return (RawRecord.Deleted) store.get(SOURCE, id).orElseThrow();
  }

  /** m1을 다른 순서 scheme(other.v1)으로 덮으려는 upsert. */
  static final RawMutation OTHER_SCHEME_UPSERT =
      RawItems.item("m1").version("v9").order(new SourceOrder("other.v1", "0009")).upsert();

  /** m1을 0002 순서에서 지운다(발생 OCCURRED, 관측 OBSERVED). */
  Outcome deleteM1At0002() {
    return delete(
        "m1", Optional.empty(), Optional.of(order("0002")), Optional.of(OCCURRED), OBSERVED);
  }

  static List<String> ids(RawItemStore.Page page) {
    return page.items().stream().map(RawItem::externalId).toList();
  }

  // ---- F4 upsert ----

  @Test
  @DisplayName("새 키의 upsert는 upserted다")
  void newKeyIsUpserted() {
    // when
    Outcome outcome = upsert("m1", "v1", "0001");

    // then
    assertThat(outcome).isEqualTo(Outcome.UPSERTED);
  }

  @Nested
  @DisplayName("m1(v1, 순서 0001)이 active인 상태에서")
  class V1Active {
    @BeforeEach
    void givenV1() {
      upsert("m1", "v1", "0001");
    }

    @Test
    @DisplayName("같은 sourceVersion 재수집은 unchanged다")
    void sameVersionIsUnchanged() {
      // when
      Outcome outcome = upsert("m1", "v1", "0001");

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("더 뒤 순서의 새 버전은 upserted다")
    void laterVersionIsUpserted() {
      // when
      Outcome outcome = upsert("m1", "v2", "0002");

      // then
      assertThat(outcome).isEqualTo(Outcome.UPSERTED);
    }

    @Test
    @DisplayName("더 뒤 순서의 새 버전이 active 원본을 바꾼다")
    void laterVersionReplaces() {
      // when
      upsert("m1", "v2", "0002");

      // then
      assertThat(active("m1"))
          .extracting(RawItem::sourceVersion, RawItem::content)
          .containsExactly(
              "v2", Optional.of(new RawContent.Inline("application/json", "{\"text\":\"v2\"}")));
    }

    @Test
    @DisplayName("뒤 순서의 삭제는 deleted다")
    void laterDeleteIsDeleted() {
      // when
      Outcome outcome = deleteM1At0002();

      // then
      assertThat(outcome).isEqualTo(Outcome.DELETED);
    }

    @Test
    @DisplayName("뒤 순서의 삭제는 active 원본을 tombstone으로 바꾼다")
    void laterDeleteTombstones() {
      // when
      deleteM1At0002();

      // then
      assertThat(store.count(SOURCE)).isZero();
      assertThat(store.tombstoneCount(SOURCE)).isEqualTo(1);
    }

    @Test
    @DisplayName("tombstone은 마지막 버전·종류·삭제 시각·관측 시각을 담는다")
    void tombstoneKeepsProvenance() {
      // when
      deleteM1At0002();

      // then
      assertThat(tombstone("m1"))
          .extracting(
              RawRecord.Deleted::lastSourceVersion,
              RawRecord.Deleted::resourceType,
              RawRecord.Deleted::deletedAt,
              RawRecord.Deleted::observedAt)
          .containsExactly(Optional.of("v1"), Optional.of("slack.message"), OCCURRED, OBSERVED);
    }
  }

  @Nested
  @DisplayName("m1(v2, 순서 0002)이 active인 상태에서")
  class V2Active {
    @BeforeEach
    void givenV2() {
      upsert("m1", "v2", "0002");
    }

    @Test
    @DisplayName("과거 순서의 upsert는 unchanged다")
    void olderOrderIsIgnored() {
      // when
      Outcome outcome = upsert("m1", "v1", "0001");

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("같은 순서의 다른 버전은 unchanged다")
    void sameOrderIsIgnored() {
      // when
      Outcome outcome = upsert("m1", "v3", "0002");

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("순서 없는 upsert는 unchanged다")
    void unorderedAfterOrderedIsIgnored() {
      // when
      Outcome outcome = upsert("m1", "v3", null);

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("순서 없는 upsert는 원본을 바꾸지 않는다")
    void unorderedAfterOrderedKeepsItem() {
      // when
      upsert("m1", "v3", null);

      // then
      assertThat(active("m1").sourceVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("scheme이 다른 순서는 계약 위반이다")
    void schemeMismatchIsViolation() {
      // when·then
      assertThatThrownBy(() -> store.apply(SOURCE, OTHER_SCHEME_UPSERT))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("scheme이 다른 순서의 upsert는 거부되고, 원본을 바꾸지 않는다")
    void schemeMismatchKeepsItem() {
      // when
      Throwable violation = catchThrowable(() -> store.apply(SOURCE, OTHER_SCHEME_UPSERT));

      // then
      assertThat(violation).isInstanceOf(IllegalArgumentException.class);
      assertThat(active("m1").sourceVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("과거 순서의 삭제는 unchanged다")
    void olderDeleteIsIgnored() {
      // when
      Outcome outcome =
          delete("m1", Optional.empty(), Optional.of(order("0001")), Optional.empty(), T0);

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("과거 순서의 삭제는 active 원본을 지우지 않는다")
    void olderDeleteKeepsItem() {
      // when
      delete("m1", Optional.empty(), Optional.of(order("0001")), Optional.empty(), T0);

      // then
      assertThat(active("m1").sourceVersion()).isEqualTo("v2");
    }
  }

  // ---- F4 delete ----

  @Test
  @DisplayName("없던 키의 삭제도 deleted다")
  void deletingUnknownKeyIsDeleted() {
    // when
    Outcome outcome = delete("never", Optional.of("vx"), Optional.empty(), Optional.empty(), T0);

    // then
    assertThat(outcome).isEqualTo(Outcome.DELETED);
  }

  @Test
  @DisplayName("없던 키의 삭제도 tombstone을 남긴다")
  void deletingUnknownKeyTombstones() {
    // when
    delete("never", Optional.of("vx"), Optional.empty(), Optional.empty(), T0);

    // then
    assertThat(tombstone("never").lastSourceVersion()).contains("vx");
  }

  @Nested
  @DisplayName("m1(v1, 0001)을 순서 0002에서 지운 상태에서")
  class Tombstoned {
    @BeforeEach
    void givenDeletedAfterUpsert() {
      upsert("m1", "v1", "0001");
      if (deleteM1At0002() != Outcome.DELETED)
        throw new IllegalStateException("given 준비 실패: m1 삭제가 deleted가 아니다");
    }

    @Test
    @DisplayName("과거 순서의 삭제는 unchanged다")
    void olderDeleteIsIgnored() {
      // when
      Outcome outcome =
          delete("m1", Optional.empty(), Optional.of(order("0001")), Optional.empty(), T0);

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("더 뒤 순서의 upsert는 upserted다")
    void laterUpsertAfterTombstoneIsUpserted() {
      // when
      Outcome outcome = upsert("m1", "v3", "0003");

      // then
      assertThat(outcome).isEqualTo(Outcome.UPSERTED);
    }

    @Test
    @DisplayName("더 뒤 순서의 upsert는 원본을 복원한다")
    void laterUpsertRestores() {
      // when
      upsert("m1", "v3", "0003");

      // then
      assertThat(store.get(SOURCE, "m1").orElseThrow()).isInstanceOf(RawRecord.Active.class);
    }

    @Test
    @DisplayName("과거 순서의 upsert는 unchanged다")
    void olderUpsertAfterTombstoneIsIgnored() {
      // when
      Outcome outcome = upsert("m1", "v0", "0001");

      // then
      assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("과거 순서의 upsert는 원본을 되살리지 않는다")
    void olderUpsertDoesNotRevive() {
      // when
      upsert("m1", "v0", "0001");

      // then
      assertThat(store.get(SOURCE, "m1").orElseThrow()).isInstanceOf(RawRecord.Deleted.class);
    }
  }

  // ---- 목록 ----

  @Nested
  @DisplayName("active c·a·d, tombstone b, 다른 소스의 z가 있는 상태에서")
  class Listing {
    @BeforeEach
    void givenActiveTombstoneAndOtherSource() {
      for (String id : List.of("c", "a", "b", "d")) upsert(id, "v1", null);
      delete("b", Optional.empty(), Optional.empty(), Optional.empty(), T0);
      store.apply(OTHER, new RawMutation.Upsert(item("z", "v1", null)));
    }

    @Test
    @DisplayName("listActive 첫 페이지는 tombstone을 뺀 externalId 순이다")
    void listActiveFirstPage() {
      // when
      RawItemStore.Page p1 = store.listActive(SOURCE, PAGE, Optional.empty());

      // then
      assertThat(ids(p1)).containsExactly("a", "c");
    }

    @Test
    @DisplayName("listActive는 남은 원본이 있으면 다음 커서를 준다")
    void listActiveFirstPageHasCursor() {
      // when
      RawItemStore.Page p1 = store.listActive(SOURCE, PAGE, Optional.empty());

      // then
      assertThat(p1.nextCursor()).isPresent();
    }

    @Test
    @DisplayName("listActive의 마지막 페이지에는 다음 커서가 없다")
    void listActiveLastPage() {
      // given
      Optional<String> cursor = store.listActive(SOURCE, PAGE, Optional.empty()).nextCursor();

      // when
      RawItemStore.Page p2 = store.listActive(SOURCE, PAGE, cursor);

      // then
      assertThat(p2)
          .extracting(RawItemStoreContract::ids, RawItemStore.Page::nextCursor)
          .containsExactly(List.of("d"), Optional.empty());
    }

    @Test
    @DisplayName("active 수는 소스별로 격리된다")
    void countIsPerSource() {
      // when
      int here = store.count(SOURCE);
      int other = store.count(OTHER);

      // then
      assertThat(here).isEqualTo(3);
      assertThat(other).isEqualTo(1);
    }
  }

  // ---- F5 reconcile ----

  @Nested
  @DisplayName("active a·b와 tombstone c가 있는 상태에서 a만 관측해 reconcile하면")
  class ReconcileObservingOnlyA {
    @BeforeEach
    void givenActiveAbTombstoneC() {
      for (String id : List.of("a", "b", "c")) upsert(id, "v1", "0001");
      delete("c", Optional.empty(), Optional.of(order("0002")), Optional.empty(), T0);
    }

    List<String> reconcile() {
      return store.tombstoneUnobserved(SOURCE, Set.of("a"), RECONCILED);
    }

    @Test
    @DisplayName("관측되지 않은 active의 id만 돌려준다")
    void tombstonesOnlyUnobservedActive() {
      // when
      List<String> tombstoned = reconcile();

      // then
      assertThat(tombstoned).containsExactly("b");
    }

    @Test
    @DisplayName("관측된 원본은 active로 남는다")
    void observedItemStaysActive() {
      // when
      reconcile();

      // then
      assertThat(store.get(SOURCE, "a").orElseThrow()).isInstanceOf(RawRecord.Active.class);
    }

    @Test
    @DisplayName("reconcile tombstone은 reconcile 시각과 마지막 버전만 담는다")
    void reconcileTombstoneProvenance() {
      // when
      reconcile();

      // then
      assertThat(tombstone("b"))
          .extracting(
              RawRecord.Deleted::deletedAt,
              RawRecord.Deleted::lastSourceVersion,
              RawRecord.Deleted::sourceOrder)
          .containsExactly(RECONCILED, Optional.of("v1"), Optional.empty());
    }

    @Test
    @DisplayName("다시 reconcile해도 새로 지울 것이 없다")
    void reconcileIsIdempotent() {
      // given
      reconcile();

      // when
      List<String> again = reconcile();

      // then
      assertThat(again).isEmpty();
    }
  }

  @Test
  @DisplayName("관측 0건 reconcile은 소스의 active를 모두 tombstone한다(TS와 같음)")
  void reconcileWithNoObservationTombstonesAllActive() {
    // given
    for (String id : List.of("a", "b")) upsert(id, "v1", "0001");

    // when
    List<String> tombstoned = store.tombstoneUnobserved(SOURCE, Set.of(), RECONCILED);

    // then
    assertThat(tombstoned).containsExactlyInAnyOrder("a", "b");
  }

  // ---- F8 blob ----

  @Nested
  @DisplayName("64KiB를 넘는 콘텐츠의 원본을 적용한 상태에서")
  class BigContentApplied {
    RawContent.Blob stored;

    @BeforeEach
    void givenBigContentApplied() {
      RawContent captured = RawContentCapture.defaults().json("application/json", BIG_JSON);
      if (!(captured instanceof RawContent.Blob))
        throw new IllegalStateException("given 준비 실패: 큰 콘텐츠가 blob으로 잡히지 않았다");
      Outcome outcome =
          store.apply(
              SOURCE, new RawMutation.Upsert(item("big", "v1", null).withContent(captured)));
      if (outcome != Outcome.UPSERTED)
        throw new IllegalStateException("given 준비 실패: 큰 원본이 적용되지 않았다: " + outcome);
      stored = (RawContent.Blob) active("big").content().orElseThrow();
    }

    @Test
    @DisplayName("저장된 원본은 데이터 없이 blob 참조만 가진다")
    void bigContentIsStoredAsReference() {
      // then
      assertThat(stored.data()).isEmpty();
    }

    @Test
    @DisplayName("blob 데이터는 sha256으로 읽는다")
    void blobRoundTrip() {
      // when
      Optional<byte[]> data = store.blob(stored.sha256());

      // then
      assertThat(new String(data.orElseThrow(), StandardCharsets.UTF_8)).isEqualTo(BIG_JSON);
    }
  }

  @Test
  @DisplayName("없는 sha256의 blob은 비어 있다")
  void missingBlobIsEmpty() {
    // when
    Optional<byte[]> data = store.blob(MISSING_SHA);

    // then
    assertThat(data).isEmpty();
  }
}
