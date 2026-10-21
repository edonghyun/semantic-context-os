package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.ScoredRef;
import io.scos.store.postgres.KeywordPlan.Level;
import io.scos.store.postgres.KeywordPlan.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 키워드 후보 경로 계획(ADR-0014 P-4 부록): 드문 bigram 구동 경로, 2단계 재조회 경로, 1단계 밖 유닛의 점수 상한. 결과 등가는
 * PostgresKeywordIndexEquivalenceTest가 본다. DB 없음.
 */
@DisplayName("키워드 질의 계획은 가장 드문 bigram으로 후보 경로를 고르고 점수 상한을 낸다")
class KeywordPlanTest {
  /** 드묾 상한 — 아래 문서 빈도의 10은 드물고 1,000은 흔하다. */
  static final int LIMIT = 100;

  /** "2026년"의 bigram: 숫자 bigram은 흔하고 "6년"만 드물다(운영 분포). */
  static final Map<String, Integer> DATE_DF =
      Map.of("20", 1_000, "02", 1_000, "26", 1_000, "6년", 10);

  static KeywordPlan plan(Map<String, Integer> df, String... tokens) {
    return KeywordPlan.of(List.of(tokens), df, LIMIT);
  }

  static List<ScoredRef> scores(double... scores) {
    return IntStream.range(0, scores.length)
        .mapToObj(i -> new ScoredRef("u" + i, "d", scores[i]))
        .toList();
  }

  @Test
  @DisplayName("탐침할 bigram은 2글자 이상 토큰의 bigram을 중복 없이 처음 나온 순서로 모은다")
  void bigramsOfMultiCodePointTokens() {
    // when·then
    assertThat(KeywordPlan.bigramsOf(List.of("정산", "가", "정산일", "산정")))
        .containsExactly("정산", "산일", "산정");
  }

  @Test
  @DisplayName("토큰은 질의 토큰 순서이고 되풀이된 토큰도 따로 둔다")
  void tokensKeepQueryOrderWithRepeats() {
    // when
    KeywordPlan plan = plan(Map.of(), "정산", "가", "정산");

    // then
    assertThat(plan.tokens()).extracting(KeywordPlan.Token::text).containsExactly("정산", "가", "정산");
  }

  @Nested
  @DisplayName("3글자 이상 토큰의 trigram마다 드문 bigram이 있을 때")
  class RareTrigrams {
    final KeywordPlan plan = plan(Map.of("정산", 10, "산일", 5, "일자", 50), "정산일자");

    @Test
    @DisplayName("trigram마다 더 드문 bigram이 공백 제거 본문 경로를 구동한다")
    void trigramPathsUseRarerBigram() {
      // then
      assertThat(plan.paths(Level.RARE))
          .containsExactly(new Path("산일", true, "%정산일%"), new Path("산일", true, "%산일자%"));
    }

    @Test
    @DisplayName("trigram 경로가 후보 전체를 덮으니 상한은 0이다")
    void boundIsZero() {
      // then
      assertThat(plan.bound(Level.RARE)).isZero();
    }
  }

  @Nested
  @DisplayName("\"2026년\"처럼 trigram에 흔한 bigram만 있는 토큰이면")
  class CommonTrigrams {
    final KeywordPlan plan = plan(DATE_DF, "2026년");

    @Test
    @DisplayName("1단계는 토큰의 가장 드문 bigram으로 원문 정확 매치만 찾는다")
    void rarePathIsExactMatchByRarestBigram() {
      // then
      assertThat(plan.paths(Level.RARE)).containsExactly(new Path("6년", false, "%2026년%"));
    }

    @Test
    @DisplayName("2단계는 trigram 경로 전부로 후보 전체를 덮는다")
    void fallbackCoversAllTrigrams() {
      // then
      assertThat(plan.paths(Level.ALL))
          .containsExactly(
              new Path("20", true, "%202%"),
              new Path("02", true, "%026%"),
              new Path("6년", true, "%26년%"));
    }

    @Test
    @DisplayName("정확 매치 밖 유닛은 커버리지만 받으니 상한은 1이다")
    void boundIsOne() {
      // then
      assertThat(plan.bound(Level.RARE)).isEqualTo(1);
    }

    @Test
    @DisplayName("EXACT 단계가 빼놓은 trigram 경로의 구동 bigram을 낸다")
    void omittedDriversOfExactLevel() {
      // then
      assertThat(plan.omittedDrivers(Level.EXACT)).containsExactly("20", "02", "6년");
    }

    @Test
    @DisplayName("EXACT 단계는 흔한 trigram 경로를 빼고 정확 매치 경로만 쓰니 상한이 1로 남는다")
    void exactLevelKeepsBoundOne() {
      // then
      assertThat(plan.bound(Level.EXACT)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("bigram이 모두 흔한 토큰은 1단계 경로가 없고 상한 2를 더한다")
  void allCommonTokenAddsTwo() {
    // when
    KeywordPlan plan = plan(DATE_DF, "2026", "20");

    // then
    assertThat(plan.paths(Level.RARE)).isEmpty();
    assertThat(plan.bound(Level.RARE)).isEqualTo(4);
  }

  @Test
  @DisplayName("2글자 토큰은 드물면 원문 경로이고 상한을 더하지 않는다")
  void rareTwoCodePointTokenIsExactPath() {
    // when
    KeywordPlan plan = plan(Map.of("정산", LIMIT), "정산");

    // then
    assertThat(plan.paths(Level.RARE)).containsExactly(new Path("정산", false, "%정산%"));
    assertThat(plan.bound(Level.RARE)).isZero();
  }

  @Test
  @DisplayName("1글자 토큰은 2단계 전체 스캔 경로가 되고 상한 1을 더한다")
  void singleCodePointTokenIsFullScan() {
    // when
    KeywordPlan plan = plan(Map.of(), "%");

    // then
    assertThat(plan.paths(Level.ALL)).containsExactly(new Path(null, false, "%\\%%"));
    assertThat(plan.bound(Level.RARE)).isEqualTo(1);
  }

  @Test
  @DisplayName("문서 빈도가 같으면 토큰에서 먼저 나온 bigram이 구동한다")
  void tieGoesToFirstBigram() {
    // when
    KeywordPlan plan = plan(Map.of("20", 1_000, "02", 1_000, "26", 5, "6년", 5), "2026년");

    // then
    assertThat(plan.paths(Level.RARE)).containsExactly(new Path("26", false, "%2026년%"));
  }

  @Test
  @DisplayName("문서 빈도를 모르는 bigram은 흔한 것으로 본다")
  void unknownBigramIsCommon() {
    // when
    KeywordPlan plan = plan(Map.of(), "정산");

    // then
    assertThat(plan.paths(Level.RARE)).isEmpty();
  }

  @Nested
  @DisplayName("1단계 밖 유닛의 점수 상한이 1일 때")
  class Settling {
    final KeywordPlan plan = plan(DATE_DF, "2026년");

    @Test
    @DisplayName("k개를 채우고 k번째 점수가 상한보다 크면 확정이다")
    void settlesAboveBound() {
      // then
      assertThat(plan.settles(Level.RARE, scores(1.9, 1.0001), 2)).isTrue();
    }

    @Test
    @DisplayName("k번째 점수가 상한과 같으면 seq가 앞선 밖 유닛이 끼어들 수 있어 미확정이다")
    void tieWithBoundDoesNotSettle() {
      // then
      assertThat(plan.settles(Level.RARE, scores(1.9, 1.0), 2)).isFalse();
    }

    @Test
    @DisplayName("k개를 못 채우면 밖 유닛이 남은 자리를 채울 수 있어 미확정이다")
    void shortResultDoesNotSettle() {
      // then
      assertThat(plan.settles(Level.RARE, scores(1.9), 2)).isFalse();
    }

    @Test
    @DisplayName("k가 0이면 결과가 비어도 확정이다")
    void zeroKSettles() {
      // then
      assertThat(plan.settles(Level.RARE, List.of(), 0)).isTrue();
    }
  }

  @Test
  @DisplayName("상한이 0이면 결과가 k개보다 적어도 확정이다")
  void zeroBoundSettlesShortResult() {
    // given
    KeywordPlan plan = plan(Map.of("정산", 1), "정산");

    // when·then
    assertThat(plan.settles(Level.RARE, scores(0.5), 10)).isTrue();
  }

  @Test
  @DisplayName("EXACT 단계는 흔한 2글자 토큰과 1글자 토큰의 후보를 빈도와 무관하게 전부 덮는다")
  void exactLevelCoversCommonShortTokens() {
    // when
    KeywordPlan plan = plan(DATE_DF, "20", "년");

    // then
    assertThat(plan.paths(Level.EXACT))
        .containsExactly(new Path("20", false, "%20%"), new Path(null, false, "%년%"));
    assertThat(plan.bound(Level.EXACT)).isZero();
  }

  @Test
  @DisplayName("bigram이 모두 흔한 긴 토큰도 EXACT 단계는 가장 드문 bigram으로 정확 매치를 찾는다")
  void exactLevelDrivesAllCommonTokenByRarest() {
    // when
    KeywordPlan plan = plan(Map.of("20", 3_000, "02", 2_000, "26", 1_000), "2026");

    // then
    assertThat(plan.paths(Level.EXACT)).containsExactly(new Path("26", false, "%2026%"));
  }
}
