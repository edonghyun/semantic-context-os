package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.internal.search.QuestionSearch.Candidate;
import io.scos.engine.internal.search.QuestionSearch.Result;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** CONTRACTS B2–B9 순서 성질(키워드 점수 수식은 어댑터 소유라 수치는 보지 않는다). */
@DisplayName("질문 검색의 후보 규칙 — CONTRACTS B2–B9")
class QuestionSearchTest {
  static final Clock NOW = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

  /** 키워드 전용 사례용: 영벡터 → 벡터 채널 결과 없음(임베더는 필수). */
  static final Embedder ZERO = FixedEmbedder.zero(2);

  /** 결정론 임베더: 질의 "정산"이면 settle 쪽 벡터, 아니면 직교 벡터. */
  static final Embedder EMBEDDER =
      FixedEmbedder.byKeyword("정산", new double[] {1, 0}, new double[] {0, 1});

  SearchFixture fx = new SearchFixture();

  QuestionSearch search(Embedder embedder) {
    return search(embedder, SearchSettings.DEFAULT);
  }

  QuestionSearch search(Embedder embedder, SearchSettings settings) {
    return new QuestionSearch(
        fx.units,
        fx.keywords,
        fx.vectors,
        embedder,
        Reranker.DISABLED,
        settings,
        NOW,
        TestMetrics.search(),
        TestExecutors.CALLER_THREAD);
  }

  /** 운영 조립처럼 키워드 채널을 별 스레드에서 돌리는 검색. */
  QuestionSearch searchOnLexicalThread(Embedder embedder) {
    return new QuestionSearch(
        fx.units,
        fx.keywords,
        fx.vectors,
        embedder,
        Reranker.DISABLED,
        SearchSettings.DEFAULT,
        NOW,
        TestMetrics.search(),
        lexicalThread);
  }

  final ExecutorService lexicalThread = Executors.newSingleThreadExecutor();

  @AfterEach
  void stopLexicalThread() {
    lexicalThread.shutdownNow();
  }

  /** 키워드만·벡터만·양쪽 매치, 한 문서의 여러 유닛, 링크 자식, archived·compacted가 섞인 공간. */
  void givenMixedChannels() {
    fx.load(
        S,
        unit("kw-1").inDocument("kw").content("정산 배치 실행 기록").embedding(0, 1).build(),
        unit("kw-2").inDocument("kw").content("정산 배치 재시도").embedding(0, 1).build(),
        unit("kw-3").inDocument("kw").content("정산 배치 실패").embedding(0, 1).build(),
        unit("vec-1").inDocument("vec").content("결제 안내").embedding(1, 0).build(),
        unit("both-1").inDocument("both").content("정산 요약").embedding(1, 0).linksTo("child").build(),
        unit("child-1").inDocument("child").content("링크된 자식 문서").build(),
        unit("arch-1").inDocument("arch").content("정산 배치 옛 규정").archived().build(),
        unit("sum-1").inDocument("sum").content("정산 배치 요약").compacted("kw-1").build());
  }

  static List<String> ids(Result r) {
    return r.candidates().stream().map(c -> c.unit().id()).toList();
  }

  /** 결과에서 유닛의 후보. 없으면 then까지 가지 않고 멈춘다. */
  static Candidate candidate(Result r, String unitId) {
    return r.candidates().stream()
        .filter(c -> c.unit().id().equals(unitId))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("given 준비 실패: 후보에 " + unitId + "가 없다 — " + ids(r)));
  }

  @Nested
  @DisplayName("키워드 채널만 결과를 낼 때(영벡터 임베더)")
  class KeywordOnly {
    @Test
    @DisplayName("질의 토큰을 하나도 갖지 않은 유닛은 후보가 아니다")
    void excludesUnitsWithoutQueryToken() {
      // given
      fx.load(
          S,
          unit("a-1").inDocument("a").content("정산 배치 1").build(),
          unit("e-1").inDocument("e").content("온보딩 가이드").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("a-1");
    }

    @Test
    @DisplayName("한 문서의 유닛은 점수 순으로 2개까지만 후보가 된다(문서당 상한)")
    void capsUnitsPerDocument() {
      // given
      fx.load(
          S,
          unit("a-1").inDocument("a").content("정산 배치 1").build(),
          unit("a-2").inDocument("a").content("정산 배치 2").build(),
          unit("a-3").inDocument("a").content("정산 배치 3").build(),
          unit("b-1").inDocument("b").content("정산 규정").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then: a-* 셋은 동점이라 적재 순으로 앞의 둘만 남는다
      assertThat(ids(r)).containsExactlyInAnyOrder("a-1", "a-2", "b-1");
    }

    @Test
    @DisplayName("compacted 유닛은 문서당 상한에 세지 않는다")
    void compactedIsNotCapped() {
      // given: 요약을 먼저 적재해 동점 순서에서 raw 둘보다 앞에 두면, 요약을 세는 구현은 a-2를 잘라낸다
      fx.load(
          S,
          unit("a-sum").inDocument("a").content("정산 요약").compacted("a-1", "a-2").build(),
          unit("a-1").inDocument("a").content("정산 배치 1").build(),
          unit("a-2").inDocument("a").content("정산 배치 2").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactlyInAnyOrder("a-sum", "a-1", "a-2");
    }

    @Test
    @DisplayName("compacted 유닛은 점수와 무관하게 raw 앞에 선다(티어 우선)")
    void compactedRanksFirst() {
      // given
      fx.load(
          S,
          unit("a-1").inDocument("a").content("정산 정산 정산 배치").build(),
          unit("b-sum").inDocument("b").content("정산 요약").compacted().build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("b-sum", "a-1");
      assertThat(r.candidates())
          .extracting(Candidate::baseScore)
          .isSortedAccordingTo((x, y) -> Double.compare(y, x));
    }

    @Test
    @DisplayName("invalidated 유닛은 후보가 되지 않는다")
    void excludesInvalidated() {
      // given
      fx.load(
          S,
          unit("a-1").inDocument("a").content("정산 배치").build(),
          unit("c-1")
              .inDocument("c")
              .content("정산 폐기")
              .invalidatedAt("2026-10-02T00:00:00.000Z")
              .build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("a-1");
    }

    @Test
    @DisplayName("먼저 적재된 archived 유닛도 가중 0.3 때문에 같은 키워드의 raw 뒤에 선다")
    void archivedRanksBehindRaw() {
      // given
      givenArchivedLoadedBeforeRaw();

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("raw", "arch");
    }

    @Test
    @DisplayName("archived 가중은 정확히 0.3배다(키워드 1위 1/61 → 0.3/61, raw는 2위 1/62)")
    void archivedWeightIsExactly03() {
      // given
      givenArchivedLoadedBeforeRaw();

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(r.candidates())
          .extracting(Candidate::baseScore)
          .containsExactly(1.0 / 62, 1.0 / 61 * 0.3);
    }

    /** 키워드 채널 순위: arch 1위, raw 2위. */
    void givenArchivedLoadedBeforeRaw() {
      fx.load(
          S,
          unit("arch").inDocument("a").content("정산").archived().build(),
          unit("raw").inDocument("r").content("정산").build());
    }

    @Test
    @DisplayName("후보는 점수 내림차순이다 — 적재 순과 반대인 점수로 정렬을 확인")
    void candidatesAreSortedByScoreDescending() {
      // given
      fx.load(
          S,
          unit("weak").inDocument("w").content("정산").build(),
          unit("strong").inDocument("s").content("정산 정산 정산 정산").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("strong", "weak");
      assertThat(r.candidates().get(0).baseScore())
          .isGreaterThan(r.candidates().get(1).baseScore());
    }

    @Test
    @DisplayName("freshness는 공간의 최신 capturedAt과 그 시각 기준 stale 여부다")
    void reportsFreshness() {
      // given
      fx.load(
          S,
          unit("a-1").inDocument("a").content("정산").capturedAt("2026-10-01T00:00:00.000Z").build(),
          unit("b-1").inDocument("b").content("정산").capturedAt("2026-10-05T00:00:00.000Z").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(r.freshness().latestCapturedAt()).contains("2026-10-05T00:00:00.000Z");
      assertThat(r.freshness().stale()).as("8일 전 → stale").isTrue();
    }

    @Test
    @DisplayName("최신성 표지 질의('최근 …')는 날짜 가중 랭커를 타서 더 최근 문서가 앞선다")
    void prefersRecent() {
      // given: 표지 없으면 동점이라 적재 순(옛 문서가 앞)
      fx.load(
          S,
          unit("old-1")
              .inDocument("old")
              .titled("정산 현황")
              .content("정산 현황 옛")
              .capturedAt("2026-01-01T00:00:00.000Z")
              .build(),
          unit("new-1")
              .inDocument("new")
              .titled("정산 현황")
              .content("정산 현황 새")
              .capturedAt("2026-10-12T00:00:00.000Z")
              .build());
      Result plain = search(ZERO).search(AccessScope.singleUser(), S, "정산 현황");
      if (!ids(plain).equals(List.of("old-1", "new-1")))
        throw new IllegalStateException("given 준비 실패: 표지 없는 질의는 동점이라 적재 순이어야 한다 — " + ids(plain));

      // when
      Result recent = search(ZERO).search(AccessScope.singleUser(), S, "최근 정산 현황");

      // then
      assertThat(ids(recent)).containsExactly("new-1", "old-1");
    }
  }

  @Nested
  @DisplayName("키워드·벡터·링크 후보가 섞인 공간에서")
  class MixedChannels {
    @BeforeEach
    void mixedChannels() {
      givenMixedChannels();
    }

    @Test
    @DisplayName("키워드 채널을 별 스레드에서 돌려도 후보와 점수는 호출 스레드와 같다")
    void lexicalThreadGivesSameCandidates() {
      // given: 호출 스레드 결과에 키워드·벡터·링크 후보가 모두 있다
      Result onCallerThread = search(EMBEDDER).search(AccessScope.singleUser(), S, "정산 배치");
      if (!ids(onCallerThread).containsAll(List.of("kw-1", "vec-1", "child-1")))
        throw new IllegalStateException(
            "given 준비 실패: 키워드·벡터·링크 후보가 모두 있어야 한다 — " + ids(onCallerThread));

      // when
      Result onLexicalThread =
          searchOnLexicalThread(EMBEDDER).search(AccessScope.singleUser(), S, "정산 배치");

      // then
      assertThat(onLexicalThread.candidates()).isEqualTo(onCallerThread.candidates());
    }
  }

  @Nested
  @DisplayName("벡터 채널이 결과를 낼 때(\"정산\" 질의는 settle 벡터)")
  class VectorChannel {
    @Test
    @DisplayName("벡터 채널만 맞는 유닛도 코사인 0.5 이상이면 RRF로 합쳐져 후보가 된다")
    void vectorChannel() {
      // given
      fx.load(
          S,
          unit("s-1").inDocument("s").titled("결제").content("결제 안내").embedding(1, 0).build(),
          unit("t-1").inDocument("t").titled("온보딩").content("온보딩 안내").embedding(0, 1).build());

      // when
      Result r = search(EMBEDDER).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("s-1");
      assertThat(r.candidates().get(0).baseScore()).isEqualTo(1.0 / 61);
    }

    @Test
    @DisplayName("코사인이 임계 0.5 아래(0.49)인 유닛은 벡터 채널 후보가 아니다")
    void vectorBelowThresholdIsExcluded() {
      // given
      fx.load(
          S,
          unit("match").inDocument("m").titled("결제").content("결제").embedding(1, 0).build(),
          unit("below")
              .inDocument("b")
              .titled("결제")
              .content("결제")
              .embedding(0.49, Math.sqrt(1 - 0.49 * 0.49))
              .build());

      // when
      Result r = search(EMBEDDER).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("match");
    }

    @Test
    @DisplayName("벡터 임계 0.5는 경계 포함이다 — 코사인이 정확히 0.5인 유닛은 후보다(4차원 정확 표현)")
    void vectorThresholdIncludesExactHalf() {
      // given
      Embedder four =
          FixedEmbedder.byKeyword("정산", new double[] {1, 0, 0, 0}, new double[] {0, 1, 0, 0});
      fx.load(
          S,
          unit("half")
              .inDocument("h")
              .titled("결제")
              .content("결제")
              .embedding(0.5, 0.5, 0.5, 0.5)
              .build());

      // when
      Result r = search(four).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("half");
      assertThat(r.candidates().get(0).baseScore()).isEqualTo(1.0 / 61);
    }

    @Test
    @DisplayName("키워드와 벡터 양쪽에 있는 유닛은 두 채널의 RRF 항이 더해진다(1/61 + 1/61)")
    void channelsAreSummed() {
      // given
      fx.load(S, unit("both").inDocument("b").content("정산").embedding(1, 0).build());

      // when
      Result r = search(EMBEDDER).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(r.candidates().get(0).baseScore()).isEqualTo(1.0 / 61 + 1.0 / 61);
    }

    @Test
    @DisplayName("채널 가중(vectorWeight 0.5)은 RRF 항에 곱해진다")
    void channelWeightScalesRrfTerm() {
      // given
      fx.load(S, unit("s-1").inDocument("s").titled("결제").content("결제").embedding(1, 0).build());
      SearchSettings halfVector = SearchSettingsBuilder.defaults().vectorWeight(0.5).build();

      // when
      Result r = search(EMBEDDER, halfVector).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(r.candidates().get(0).baseScore()).isEqualTo(0.5 / 61);
    }

    @Test
    @DisplayName("문서당 상한은 적재 순이 아니라 점수 순으로 센다")
    void documentCapCountsInScoreOrder() {
      // given
      fx.load(
          S,
          unit("b-1").inDocument("b").content("정산 정산").build(),
          unit("a-lex").inDocument("a").content("정산").build(),
          unit("a-vec").inDocument("a").titled("결제").content("결제").embedding(1, 0).build());
      SearchSettings onePerDoc = SearchSettingsBuilder.defaults().perDocument(1).build();

      // when
      Result r = search(EMBEDDER, onePerDoc).search(AccessScope.singleUser(), S, "정산");

      // then: a-vec(벡터 rank0 → 1/61) > a-lex(키워드 rank1 → 1/62): 문서 a의 자리는 a-vec
      assertThat(ids(r)).containsExactly("b-1", "a-vec");
    }

    @Test
    @DisplayName("다른 공간의 유닛은 어떤 채널로도 후보가 되지 않는다")
    void spaceIsolation() {
      // given
      fx.load(
          new SpaceId("other"),
          unit("o-1").inDocument("o").titled("정산").content("정산 본문").embedding(1, 0).build());
      fx.load(
          S,
          unit("m-1")
              .inDocument("m")
              .titled("정산")
              .content("정산 본문")
              .linksTo("o")
              .embedding(1, 0)
              .build());

      // when
      Result r = search(EMBEDDER).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("m-1");
    }
  }

  @Nested
  @DisplayName("상위 후보가 다른 문서를 링크하면")
  class LinkExpansion {
    @Test
    @DisplayName("링크한 문서의 유닛이 1/(60+rank+5+1)로 뒤에 들어온다")
    void linkExpansion() {
      // given
      fx.load(
          S,
          unit("p-1")
              .inDocument("parent")
              .titled("정산 허브")
              .content("정산 허브")
              .linksTo("child")
              .build(),
          unit("ch-1").inDocument("child").titled("자식").content("링크된 자식 문서").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("p-1", "ch-1");
      assertThat(r.candidates().get(1).baseScore()).isEqualTo(1.0 / 66);
    }

    @Test
    @DisplayName("후보 유닛에는 여러 부모 중 최고 링크 기여 하나만 더한다(DisMax)")
    void linkContributionAddsToExistingAndTakesMax() {
      // given
      fx.load(
          S,
          unit("p-1").inDocument("p1").content("정산 정산 정산").linksTo("child").build(),
          unit("p-2").inDocument("p2").content("정산 정산").linksTo("child").build(),
          unit("ch-1").inDocument("child").content("정산").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then: ch-1의 키워드 점수(rank 2 → 1/63) + 최고 부모(rank 0)의 기여 1/66 — 두 부모의 합(1/66 + 1/67)이 아니다
      assertThat(candidate(r, "ch-1").baseScore()).isEqualTo(1.0 / 63 + 1.0 / 66);
    }

    @Test
    @DisplayName("링크 확장은 상위 linkParentWindow 후보의 링크만 본다")
    void linkExpansionHonorsParentWindow() {
      // given
      fx.load(
          S,
          unit("p-top").inDocument("top").content("정산 정산").linksTo("a").build(),
          unit("p-low").inDocument("low").content("정산").linksTo("b").build(),
          unit("a-1").inDocument("a").content("문서 a").build(),
          unit("b-1").inDocument("b").content("문서 b").build());
      SearchSettings window1 = SearchSettingsBuilder.defaults().linkParentWindow(1).build();

      // when
      Result r = search(ZERO, window1).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("p-top", "p-low", "a-1");
    }

    @Test
    @DisplayName("링크 확장의 부모 순위는 적재 순이 아니라 점수 순이다")
    void linkParentRankFollowsScoreNotLoadOrder() {
      // given
      fx.load(
          S,
          unit("p-arch").inDocument("pa").content("정산").archived().linksTo("ca").build(),
          unit("p-raw").inDocument("pr").content("정산").linksTo("cr").build(),
          unit("ca-1").inDocument("ca").content("자식 a").build(),
          unit("cr-1").inDocument("cr").content("자식 r").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, "정산");

      // then: raw 부모 rank 0 → 1/66, archived 부모 rank 1 → 1/67
      assertThat(List.of(candidate(r, "cr-1"), candidate(r, "ca-1")))
          .extracting(Candidate::baseScore)
          .containsExactly(1.0 / 66, 1.0 / 67);
    }
  }

  @Nested
  @DisplayName("설정으로 규칙을 끄면")
  class RulesDisabled {
    @Test
    @DisplayName("vectorMinScore가 0 이하이면 임계를 적용하지 않는다")
    void noThresholdWhenMinScoreIsZero() {
      // given
      fx.load(
          S, unit("low").inDocument("l").titled("결제").content("결제").embedding(0.1, 0.995).build());
      SearchSettings noMin = SearchSettingsBuilder.defaults().vectorMinScore(0).build();

      // when
      Result r = search(EMBEDDER, noMin).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("low");
    }

    @Test
    @DisplayName("링크 확장이 꺼져 있으면 링크 문서는 들어오지 않는다")
    void linkExpansionCanBeDisabled() {
      // given
      fx.load(
          S,
          unit("p-1").inDocument("p").content("정산").linksTo("child").build(),
          unit("ch-1").inDocument("child").content("자식").build());
      SearchSettings noLinks = SearchSettingsBuilder.defaults().linksDisabled().build();

      // when
      Result r = search(ZERO, noLinks).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("p-1");
    }

    @Test
    @DisplayName("perDocument 0 이하와 tierPriority 끔은 문서당 상한과 티어 우선을 끈다")
    void capAndTierPriorityCanBeDisabled() {
      // given
      fx.load(
          S,
          unit("a-1").inDocument("a").content("정산 정산 정산").build(),
          unit("a-2").inDocument("a").content("정산 정산").build(),
          unit("a-3").inDocument("a").content("정산").build(),
          unit("b-sum").inDocument("b").content("정산 요약").compacted().build());
      SearchSettings relaxed =
          SearchSettingsBuilder.defaults().perDocument(0).tierPriorityDisabled().build();

      // when
      Result r = search(ZERO, relaxed).search(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(ids(r)).containsExactly("a-1", "a-2", "a-3", "b-sum");
    }
  }

  @Nested
  @DisplayName("질의에 접속어가 있으면(변형 질의 \"결제 장애 그리고 배포 절차\")")
  class VariantQuery {
    static final String QUERY = "결제 장애 그리고 배포 절차";

    @Test
    @DisplayName("변형 질의의 매치는 0.9 가중이라 원질의만 맞는 유닛이 앞선다")
    void variantsCarryLowerWeight() {
      // given
      fx.load(
          S,
          unit("full").inDocument("f").content("결제 장애 배포 절차").build(),
          unit("part").inDocument("p").content("배포 절차").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, QUERY);

      // then
      assertThat(ids(r)).containsExactly("full", "part");
      assertThat(r.candidates().get(1).baseScore()).isLessThan(r.candidates().get(0).baseScore());
    }

    @Test
    @DisplayName("한 채널 안에서 원질의·변형 리스트에 다 있는 유닛은 큰 쪽 점수를 받는다(DisMax)")
    void withinChannelTakesMaxAcrossVariantLists() {
      // given
      fx.load(
          S,
          unit("y").inDocument("y").content("결제 장애 배포 절차").build(),
          unit("x").inDocument("x").content("배포 절차 배포 절차").build());

      // when
      Result r = search(ZERO).search(AccessScope.singleUser(), S, QUERY);

      // then: y는 원질의 rank0 1/61 > 변형 0.9/61, x는 원질의 rank1 1/62 > 변형 '배포 절차' rank0 0.9/61
      assertThat(List.of(candidate(r, "y"), candidate(r, "x")))
          .extracting(Candidate::baseScore)
          .containsExactly(1.0 / 61, 1.0 / 62);
    }
  }
}
