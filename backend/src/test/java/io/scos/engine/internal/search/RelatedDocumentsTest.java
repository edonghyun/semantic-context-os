package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.internal.search.RelatedDocuments.Related;
import io.scos.engine.internal.search.RelatedDocuments.Via;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CONTRACTS E: 시드 5개 → 임베딩 채널(DisMax, 자기 제외, archived 제외) ∪ 링크 채널(1홉, 최댓값 정규화) → RRF(k=60), via,
 * documentId 사전순 동점.
 */
@DisplayName("관련 문서는 임베딩 채널과 링크 채널을 RRF로 합친다(CONTRACTS E)")
class RelatedDocumentsTest {
  SearchFixture fx = new SearchFixture();
  RelatedDocuments related;

  static final int LIMIT = 10;
  static final double EXACT = 1e-12;

  /** RRF(k=60)의 1위·2위 점수. */
  static final double FIRST = 1.0 / 61;

  static final double SECOND = 1.0 / 62;

  /** 이 유닛들만 있는 공간(S)에서 hub 문서의 관련 문서. */
  static List<Related> relatedToHub(ContextUnit... units) {
    SearchFixture f = new SearchFixture();
    f.load(S, units);
    return new RelatedDocuments(f.units, f.vectors).find(S, "hub", LIMIT);
  }

  @Nested
  @DisplayName("허브 문서가 링크·임베딩 이웃과 archived·다른 공간 문서 곁에 있으면")
  class Hub {
    @BeforeEach
    void givenHubSpace() {
      fx.load(
          S,
          unit("hub-1")
              .inDocument("hub")
              .titled("허브")
              .content("허브 본문")
              .linksTo("linked", "settle")
              .embedding(1, 0, 0)
              .build(),
          unit("linked-1")
              .inDocument("linked")
              .titled("연결 1")
              .content("연결 본문")
              .linksTo("hub")
              .embedding(0.9, 0.1, 0)
              .build(),
          unit("linked-2")
              .inDocument("linked")
              .titled("연결 2")
              .content("연결 본문 2")
              .embedding(0.8, 0.2, 0)
              .build(),
          unit("settle-1")
              .inDocument("settle")
              .titled("정산")
              .content("정산 본문")
              .embedding(0, 0, 1)
              .build(),
          unit("near-1")
              .inDocument("near")
              .titled("가까운")
              .content("가까운 본문")
              .embedding(0.95, 0.05, 0)
              .build(),
          unit("arch-1")
              .inDocument("arch")
              .titled("옛 문서")
              .content("옛 본문")
              .archived()
              .embedding(1, 0, 0)
              .build());
      fx.load(
          new SpaceId("other"),
          unit("x-1").inDocument("x").titled("다른 공간").content("본문").embedding(1, 0, 0).build());
      related = new RelatedDocuments(fx.units, fx.vectors);
    }

    /**
     * 임베딩 채널 순위: near(0.9987) rank0, linked(0.994) rank1. 링크 채널: linked·settle 모두 1/1 → documentId
     * 사전순 linked rank0, settle rank1. RRF: linked = 1/62 + 1/61, near = 1/61, settle = 1/62.
     */
    List<Related> find() {
      return related.find(S, "hub", LIMIT);
    }

    @Test
    @DisplayName("링크+임베딩·임베딩만·링크만 찾은 문서 순이다")
    void ordersByChannel() {
      // when
      List<Related> got = find();

      // then
      assertThat(got).extracting(Related::documentId).containsExactly("linked", "near", "settle");
    }

    @Test
    @DisplayName("자기 문서·archived 문서·다른 공간 문서는 없다")
    void excludesSelfArchivedAndOtherSpace() {
      // when
      List<String> ids = find().stream().map(Related::documentId).toList();

      // then: 부재 앞에 존재 — 관련 문서가 있고, 그중에 없다
      assertThat(ids).isNotEmpty().doesNotContain("hub", "arch", "x");
    }

    @Test
    @DisplayName("각 문서는 자기를 찾은 채널을 via로 밝힌다")
    void reportsVia() {
      // when
      List<Related> got = find();

      // then
      assertThat(got).extracting(Related::via).containsExactly(Via.BOTH, Via.EMBEDDING, Via.LINK);
    }

    @Test
    @DisplayName("문서 제목은 그 문서 첫 유닛의 제목이다")
    void titleIsFirstUnitTitle() {
      // when
      List<Related> got = find();

      // then
      assertThat(got.get(0).title()).isEqualTo("연결 1");
    }

    @Test
    @DisplayName("점수는 두 채널 순위의 RRF 합이다")
    void scoreIsRrfSum() {
      // when
      List<Related> got = find();

      // then
      assertThat(got.get(0).score()).isCloseTo(SECOND + FIRST, within(EXACT));
      assertThat(got.get(1).score()).isCloseTo(FIRST, within(EXACT));
      assertThat(got.get(2).score()).isCloseTo(SECOND, within(EXACT));
    }

    @Test
    @DisplayName("limit만큼만 돌려준다")
    void honorsLimit() {
      // when
      List<Related> got = related.find(S, "hub", 1);

      // then
      assertThat(got).extracting(Related::documentId).containsExactly("linked");
    }

    @Test
    @DisplayName("없는 문서는 그 id의 DocumentNotFoundException이다")
    void notFound() {
      // when·then
      assertThatThrownBy(() -> related.find(S, "missing-doc", 5))
          .asInstanceOf(type(DocumentNotFoundException.class))
          .extracting(DocumentNotFoundException::documentId)
          .isEqualTo("missing-doc");
    }
  }

  /** hub가 유닛 하나씩인 zeta·alpha를 링크한다(링크 채널 동점, 임베딩 코사인 0). */
  static List<Related> relatedToTiedLinks() {
    return relatedToHub(
        unit("hub-1").inDocument("hub").linksTo("zeta", "alpha").embedding(1, 0, 0).build(),
        unit("z-1").inDocument("zeta").embedding(0, 0, 1).build(),
        unit("a-1").inDocument("alpha").embedding(0, 0, 1).build());
  }

  @Test
  @DisplayName("링크 채널에서 유닛 수가 같은 문서는 documentId 사전순으로 순위를 받는다")
  void tiesOrderByDocumentId() {
    // when
    List<Related> got = relatedToTiedLinks();

    // then
    assertThat(got).extracting(Related::documentId).containsExactly("alpha", "zeta");
  }

  @Test
  @DisplayName("링크 채널에서 유닛 수가 같은 문서도 사전순으로 받은 순위의 점수(1위·2위 RRF)를 받는다")
  void tiedDocumentsGetRankScores() {
    // when
    List<Related> got = relatedToTiedLinks();

    // then
    assertThat(got).extracting(Related::score).containsExactly(FIRST, SECOND);
  }

  @Test
  @DisplayName("링크 채널 순위는 링크된 문서의 유닛 수 내림차순이다(1홉, 최댓값 정규화)")
  void linkChannelRanksByLinkedUnitCount() {
    // when
    List<Related> got =
        relatedToHub(
            unit("h-1").inDocument("hub").linksTo("aa-small", "zz-big").build(),
            unit("s-1").inDocument("aa-small").build(),
            unit("b-1").inDocument("zz-big").build(),
            unit("b-2").inDocument("zz-big").build());

    // then: 사전순(aa-small < zz-big)과 반대 — 순위가 유닛 수에서 온다
    assertThat(got).extracting(Related::documentId).containsExactly("zz-big", "aa-small");
    assertThat(got).extracting(Related::via).containsOnly(Via.LINK);
  }

  @Test
  @DisplayName("임베딩 채널은 임베딩이 있는 시드로만 계산한다")
  void seedsWithoutVectorsAreSkipped() {
    // when
    List<Related> got =
        relatedToHub(
            unit("h-novec").inDocument("hub").build(),
            unit("h-vec").inDocument("hub").embedding(1, 0, 0).build(),
            unit("n-1").inDocument("near").embedding(1, 0, 0).build());

    // then
    assertThat(got)
        .extracting(Related::documentId, Related::via)
        .containsExactly(tuple("near", Via.EMBEDDING));
  }

  @Test
  @DisplayName("문서 유닛이 6개 이상이면 시드는 적재 순서 앞 5개다")
  void seedsAreFirstFiveUnits() {
    // when
    List<Related> got =
        relatedToHub(
            unit("h-1").inDocument("hub").build(),
            unit("h-2").inDocument("hub").build(),
            unit("h-3").inDocument("hub").build(),
            unit("h-4").inDocument("hub").build(),
            unit("h-5").inDocument("hub").linksTo("fifth").build(),
            unit("h-6").inDocument("hub").linksTo("sixth").build(),
            unit("f-1").inDocument("fifth").build(),
            unit("s-1").inDocument("sixth").build());

    // then: 다섯째 유닛의 링크는 따라가고, 여섯째 유닛의 링크는 따라가지 않는다
    assertThat(got).extracting(Related::documentId).containsExactly("fifth");
  }

  @Test
  @DisplayName("시드에 임베딩이 하나도 없으면 링크 채널 결과만 낸다")
  void noVectorsAtAllFallsBackToLinks() {
    // when
    List<Related> got =
        relatedToHub(
            unit("h-1").inDocument("hub").linksTo("linked").build(),
            unit("l-1").inDocument("linked").embedding(1, 0, 0).build());

    // then
    assertThat(got)
        .extracting(Related::documentId, Related::via)
        .containsExactly(tuple("linked", Via.LINK));
  }

  @Test
  @DisplayName("두 채널에서 찾아 점수가 높은 문서는 documentId 사전순보다 앞선다")
  void scoreBeatsLocaleOrder() {
    // when
    List<Related> got =
        relatedToHub(
            unit("hub-1").inDocument("hub").linksTo("alpha", "zeta").embedding(1, 0, 0).build(),
            unit("a-1").inDocument("alpha").embedding(0, 0, 1).build(),
            unit("z-1").inDocument("zeta").embedding(1, 0, 0).build()); // 링크 + 임베딩 → 더 높은 점수

    // then
    assertThat(got.get(0).via()).isEqualTo(Via.BOTH);
    assertThat(got).extracting(Related::documentId).containsExactly("zeta", "alpha");
  }
}
