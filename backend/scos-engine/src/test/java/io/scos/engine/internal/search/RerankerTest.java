package io.scos.engine.internal.search;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.internal.search.QuestionSearch.Candidate;
import io.scos.spi.embedding.TextGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** CONTRACTS B8 재정렬(TS LlmRerankStage): 유효 선택만 순서 변경·점수 집합 재배정, 형식/호출 실패는 원 순서 + 명시 결과, 캐시. */
@DisplayName("재정렬은 유효한 선택만 순서를 바꾸고, 실패면 원 순서다(CONTRACTS B8)")
class RerankerTest {
  static final ObjectMapper JSON = new ObjectMapper();

  /** u1…un 후보(점수 1/(60+i)). 점수를 단언하는 테스트는 {@link #scored}로 점수를 드러낸다. */
  static List<Candidate> candidates(int n) {
    List<Candidate> out = new ArrayList<>();
    for (int i = 1; i <= n; i++)
      out.add(
          new Candidate(
              unit("u" + i).inDocument("d" + i).titled("제목 " + i).content("본문 " + i).build(),
              1.0 / (60 + i)));
    return out;
  }

  /** u1…un 후보, 점수는 넘긴 순서대로. */
  static List<Candidate> scored(double... scores) {
    List<Candidate> out = new ArrayList<>();
    for (int i = 1; i <= scores.length; i++)
      out.add(new Candidate(unit("u" + i).inDocument("d" + i).build(), scores[i - 1]));
    return out;
  }

  /** 재정렬 상위 후보 수(기본 설정). */
  static final int HEAD = 30;

  static TextGenerator failing() {
    return (p, m) -> {
      throw new TextGenerator.GenerationException("Gemini API 503");
    };
  }

  /** 받은 maxTokens를 기록하고 "1"로 답한다. */
  static TextGenerator recordingMaxTokens(List<Integer> maxTokens) {
    return (prompt, max) -> {
      maxTokens.add(max);
      return "1";
    };
  }

  static TextGenerator answering(String response, AtomicInteger calls) {
    return (prompt, maxTokens) -> {
      calls.incrementAndGet();
      return response;
    };
  }

  /** 첫 호출은 first에 맡기고, 그 뒤로는 later로 답한다. 호출 수를 센다. */
  static TextGenerator firstThen(TextGenerator first, String later, AtomicInteger calls) {
    return (prompt, maxTokens) ->
        calls.incrementAndGet() == 1 ? first.generate(prompt, maxTokens) : later;
  }

  static List<String> ids(Reranker.Result r) {
    return r.candidates().stream().map(c -> c.unit().id()).toList();
  }

  @Nested
  @DisplayName("생성기가 점수 0.5·0.4·0.3·0.2인 후보 4개에 유효한 선택 3,2,1로 답하면")
  class ValidSelection {
    final AtomicInteger calls = new AtomicInteger();
    Reranker r;
    List<Candidate> in;

    @BeforeEach
    void givenValidSelection() {
      r = new LlmReranker(answering("3,2,1", calls), HEAD, JSON);
      in = scored(0.5, 0.4, 0.3, 0.2);
    }

    @Test
    @DisplayName("결과는 Applied다")
    void appliesValidSelection() {
      // when
      Reranker.Result out = r.rerank("질의", in);

      // then
      assertThat(out.outcome()).isInstanceOf(RerankOutcome.Applied.class);
    }

    @Test
    @DisplayName("그 순서로 바꾼다")
    void reordersByValidSelection() {
      // when
      Reranker.Result out = r.rerank("질의", in);

      // then
      assertThat(ids(out)).containsExactly("u3", "u2", "u1", "u4");
    }

    @Test
    @DisplayName("기존 점수 집합을 새 순서에 배정한다")
    void reassignsScoresToNewOrder() {
      // when
      Reranker.Result out = r.rerank("질의", in);

      // then
      assertThat(out.candidates())
          .extracting(Candidate::baseScore)
          .containsExactly(0.5, 0.4, 0.3, 0.2);
    }

    @Nested
    @DisplayName("같은 질의·후보로 한 번 재정렬한 뒤")
    class AfterFirstRerank {
      @BeforeEach
      void givenFirstRerank() {
        r.rerank("질의", in);
      }

      @Test
      @DisplayName("다시 부르면 캐시 결과(fromCache)를 돌려준다")
      void cachesByQueryAndCandidates() {
        // when
        Reranker.Result again = r.rerank("질의", in);

        // then
        assertThat(again.outcome())
            .isInstanceOfSatisfying(
                RerankOutcome.Applied.class, a -> assertThat(a.fromCache()).isTrue());
      }

      @Test
      @DisplayName("다시 불러도 생성기를 부르지 않는다(유료 호출 횟수가 계약)")
      void cacheHitMakesNoPaidCall() {
        // when
        r.rerank("질의", in);

        // then
        assertThat(calls.get()).isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("생성기가 그 밖의 유효한 선택으로 답하면")
  class OtherValidSelections {
    @Test
    @DisplayName("부분 선택('2')은 선택 뒤에 나머지를 원 순서로 붙인다")
    void partialSelection() {
      // given
      Reranker r = new LlmReranker(answering("2", new AtomicInteger()), HEAD, JSON);

      // when
      Reranker.Result out = r.rerank("q", candidates(3));

      // then
      assertThat(ids(out)).containsExactly("u2", "u1", "u3");
    }

    @Test
    @DisplayName("topK 밖 후보는 원 순서대로 재정렬 결과 뒤에 붙는다")
    void onlyHeadIsSentToGenerator() {
      // given
      Reranker r = new LlmReranker(answering("2,1", new AtomicInteger()), 2, JSON);

      // when
      Reranker.Result out = r.rerank("q", candidates(4));

      // then
      assertThat(ids(out)).containsExactly("u2", "u1", "u3", "u4");
    }

    @Test
    @DisplayName("재배정하는 점수 집합은 내림차순 정렬된 기존 점수다")
    void reassignsSortedScoreSet() {
      // given
      List<Candidate> unsorted =
          List.of(
              new Candidate(unit("u1").inDocument("d1").build(), 0.1),
              new Candidate(unit("u2").inDocument("d2").build(), 0.5));
      Reranker r = new LlmReranker(answering("1,2", new AtomicInteger()), HEAD, JSON);

      // when
      Reranker.Result out = r.rerank("q", unsorted);

      // then
      assertThat(out.candidates()).extracting(Candidate::baseScore).containsExactly(0.5, 0.1);
    }
  }

  @Nested
  @DisplayName("후보가 정확히 2개면(경계)")
  class TwoCandidates {
    final AtomicInteger calls = new AtomicInteger();
    Reranker r;

    @BeforeEach
    void givenAnswerTwoOne() {
      r = new LlmReranker(answering("2,1", calls), HEAD, JSON);
    }

    @Test
    @DisplayName("재정렬 결과는 Applied다")
    void twoCandidatesAreReranked() {
      // when
      Reranker.Result out = r.rerank("q", candidates(2));

      // then
      assertThat(out.outcome()).isInstanceOf(RerankOutcome.Applied.class);
    }

    @Test
    @DisplayName("선택한 순서로 바꾼다")
    void twoCandidatesAreReordered() {
      // when
      Reranker.Result out = r.rerank("q", candidates(2));

      // then
      assertThat(ids(out)).containsExactly("u2", "u1");
    }

    @Test
    @DisplayName("생성기를 한 번 부른다")
    void twoCandidatesCallGeneratorOnce() {
      // when
      r.rerank("q", candidates(2));

      // then
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("선택이 형식에 맞지 않으면(설명문·중복·범위 밖·빈 응답)")
  class MalformedSelection {
    @ParameterizedTest(name = "응답 \"{0}\"")
    @ValueSource(strings = {"가장 관련 있는 것은 2", "1,1", "0,1", "1,9", ""})
    @DisplayName("결과는 FallbackFormat이다")
    void malformedSelectionFallsBack(String response) {
      // given
      Reranker r = new LlmReranker(answering(response, new AtomicInteger()), HEAD, JSON);

      // when
      Reranker.Result out = r.rerank("q", candidates(3));

      // then
      assertThat(out.outcome()).isInstanceOf(RerankOutcome.FallbackFormat.class);
    }

    @ParameterizedTest(name = "응답 \"{0}\"")
    @ValueSource(strings = {"가장 관련 있는 것은 2", "1,1", "0,1", "1,9", ""})
    @DisplayName("원 순서를 유지한다")
    void malformedSelectionKeepsOrder(String response) {
      // given
      Reranker r = new LlmReranker(answering(response, new AtomicInteger()), HEAD, JSON);

      // when
      Reranker.Result out = r.rerank("q", candidates(3));

      // then
      assertThat(ids(out)).containsExactly("u1", "u2", "u3");
    }

    @Nested
    @DisplayName("형식 폴백 뒤 생성기가 유효한 선택 3으로 답하게 되면(TS: 실패는 캐시하지 않는다)")
    class ThenValid {
      final AtomicInteger calls = new AtomicInteger();
      Reranker r;

      @BeforeEach
      void givenFormatFallbackOnce() {
        r =
            new LlmReranker(
                firstThen(answering("1,1", new AtomicInteger()), "3", calls), HEAD, JSON);
        if (!(r.rerank("q", candidates(3)).outcome() instanceof RerankOutcome.FallbackFormat))
          throw new IllegalStateException("given 준비 실패: 첫 호출이 FallbackFormat이 아니다");
      }

      @Test
      @DisplayName("같은 질의·후보로 다시 부르면 생성기를 다시 부른다")
      void formatFallbackIsNotCached() {
        // when
        r.rerank("q", candidates(3));

        // then
        assertThat(calls.get()).isEqualTo(2);
      }

      @Test
      @DisplayName("같은 질의·후보로 다시 부르면 새 선택 순서로 회복한다")
      void recoversAfterFormatFallback() {
        // when
        Reranker.Result out = r.rerank("q", candidates(3));

        // then
        assertThat(ids(out)).containsExactly("u3", "u1", "u2");
      }
    }
  }

  @Nested
  @DisplayName("생성기가 예외를 던지면")
  class ProviderError {
    Reranker r;

    @BeforeEach
    void givenFailingGenerator() {
      r = new LlmReranker(failing(), HEAD, JSON);
    }

    @Test
    @DisplayName("결과는 FallbackError다")
    void providerErrorFallsBack() {
      // when
      Reranker.Result out = r.rerank("q", candidates(3));

      // then
      assertThat(out.outcome()).isInstanceOf(RerankOutcome.FallbackError.class);
    }

    @Test
    @DisplayName("원 순서를 유지한다")
    void providerErrorKeepsOrder() {
      // when
      Reranker.Result out = r.rerank("q", candidates(3));

      // then
      assertThat(ids(out)).containsExactly("u1", "u2", "u3");
    }

    @Nested
    @DisplayName("호출 실패 뒤 생성기가 유효한 선택 3으로 답하게 되면(TS: 실패는 캐시하지 않는다)")
    class ThenValid {
      final AtomicInteger calls = new AtomicInteger();

      @BeforeEach
      void givenErrorFallbackOnce() {
        r = new LlmReranker(firstThen(failing(), "3", calls), HEAD, JSON);
        if (!(r.rerank("q", candidates(3)).outcome() instanceof RerankOutcome.FallbackError))
          throw new IllegalStateException("given 준비 실패: 첫 호출이 FallbackError가 아니다");
      }

      @Test
      @DisplayName("같은 질의·후보로 다시 부르면 생성기를 다시 부른다(일시 503이 남지 않는다)")
      void errorFallbackIsNotCached() {
        // when
        r.rerank("q", candidates(3));

        // then
        assertThat(calls.get()).isEqualTo(2);
      }

      @Test
      @DisplayName("같은 질의·후보로 다시 부르면 새 선택 순서로 회복한다")
      void recoversAfterErrorFallback() {
        // when
        Reranker.Result out = r.rerank("q", candidates(3));

        // then
        assertThat(ids(out)).containsExactly("u3", "u1", "u2");
      }
    }
  }

  @Nested
  @DisplayName("후보가 2개 미만이면")
  class SingleCandidate {
    final AtomicInteger calls = new AtomicInteger();
    Reranker r;

    @BeforeEach
    void givenReranker() {
      r = new LlmReranker(answering("1", calls), HEAD, JSON);
    }

    @Test
    @DisplayName("결과는 Skipped다")
    void skipsSingleCandidate() {
      // when
      Reranker.Result out = r.rerank("q", candidates(1));

      // then
      assertThat(out.outcome()).isInstanceOf(RerankOutcome.Skipped.class);
    }

    @Test
    @DisplayName("생성기를 부르지 않는다")
    void singleCandidateDoesNotCallGenerator() {
      // when
      r.rerank("q", candidates(1));

      // then
      assertThat(calls.get()).isZero();
    }
  }

  @Nested
  @DisplayName("재정렬기가 비활성(DISABLED)이면")
  class Disabled {
    @Test
    @DisplayName("결과는 Skipped다")
    void disabledIsSkipped() {
      // when
      Reranker.Result out = Reranker.DISABLED.rerank("q", candidates(3));

      // then
      assertThat(out.outcome()).isInstanceOf(RerankOutcome.Skipped.class);
    }

    @Test
    @DisplayName("원 순서를 유지한다")
    void disabledKeepsOrder() {
      // when
      Reranker.Result out = Reranker.DISABLED.rerank("q", candidates(3));

      // then
      assertThat(ids(out)).containsExactly("u1", "u2", "u3");
    }
  }

  @Nested
  @DisplayName("같은 질의로 한 번 재정렬한 뒤 다른 후보로 부르면")
  class OtherCandidatesSameQuery {
    final AtomicInteger calls = new AtomicInteger();
    Reranker r;

    @BeforeEach
    void givenReranker() {
      r = new LlmReranker(answering("1", calls), HEAD, JSON);
    }

    @Test
    @DisplayName("후보 수가 같아도 id가 다르면 생성기를 다시 부른다")
    void cacheKeyIncludesCandidates() {
      // given
      r.rerank("q", candidates(2));
      List<Candidate> others =
          List.of(
              new Candidate(unit("u3").inDocument("d3").build(), 1.0 / 61),
              new Candidate(unit("u4").inDocument("d4").build(), 1.0 / 62));

      // when
      r.rerank("q", others);

      // then
      assertThat(calls.get()).as("후보 수는 같아도 id가 다르면 다른 키").isEqualTo(2);
    }

    @Test
    @DisplayName("제목·본문·시각이 같아도 id가 다르면 다시 부른다")
    void cacheKeyDistinguishesSameContentDifferentIds() {
      // given
      List<Candidate> first =
          List.of(
              new Candidate(unit("u1").titled("같은 제목").content("같은 본문").build(), 0.5),
              new Candidate(unit("u2").titled("같은 제목").content("같은 본문").build(), 0.4));
      List<Candidate> sameContentOtherIds =
          List.of(
              new Candidate(unit("v1").titled("같은 제목").content("같은 본문").build(), 0.5),
              new Candidate(unit("v2").titled("같은 제목").content("같은 본문").build(), 0.4));
      r.rerank("q", first);

      // when
      r.rerank("q", sameContentOtherIds);

      // then
      assertThat(calls.get()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("후보로 생성기 요청을 만들면")
  class GeneratorRequest {
    final LlmReranker r = new LlmReranker(answering("1", new AtomicInteger()), HEAD, JSON);

    /** 따옴표·줄바꿈·슬래시·결합 이모지가 든 후보 하나로 만든 프롬프트의 줄. */
    String[] promptLines() {
      List<Candidate> head =
          List.of(
              new Candidate(
                  unit("u1")
                      .inDocument("d1")
                      .titled("제목 \"따옴표\"")
                      .content("본문\n둘째 줄 / 슬래시 👨‍👩‍👧")
                      .build(),
                  0.5));
      return r.buildPrompt("결제 장애", head).split("\n", -1);
    }

    @Test
    @DisplayName("프롬프트는 TS buildPrompt와 같은 6줄이다")
    void promptHasSixLines() {
      // when
      String[] lines = promptLines();

      // then
      assertThat(lines).hasSize(6);
    }

    @Test
    @DisplayName("프롬프트 첫 줄은 근거 후보를 고르라는 지시다")
    void promptStartsWithInstruction() {
      // when
      String[] lines = promptLines();

      // then
      assertThat(lines[0]).startsWith("질문에 답할 구체적인 근거가 있는 후보를");
    }

    @Test
    @DisplayName("후보 줄은 TS JSON.stringify와 같은 문자열이다")
    void promptEqualsTs() {
      // when
      String[] lines = promptLines();

      // then: 키 순서·비ASCII 비이스케이프·공백 없음, title 240·content 800 UTF-16
      assertThat(lines[4])
          .isEqualTo(
              "{\"query\":\"결제 장애\",\"candidates\":[{\"number\":1,\"title\":\"제목 \\\"따옴표\\\"\",\"capturedAt\":\"2026-10-01T00:00:00.000Z\",\"content\":\"본문\\n둘째 줄 / 슬래시 👨‍👩‍👧\"}]}");
    }

    @Test
    @DisplayName("마지막 줄은 후보 수에 맞춘 출력 확인 지시다")
    void promptEndsWithCountCheck() {
      // when
      String[] lines = promptLines();

      // then
      assertThat(lines[5])
          .isEqualTo(
              "출력 확인: 후보는 총 1개다. 1부터 1 사이에서 관련 있는 번호를 1개 이상 최대 1개 선택하여 관련도 순서로 출력하라. 관련 없는 후보로 개수를 채우지 마라. 중복 없이, 설명 없이 번호 한 줄만 출력하라.");
    }

    @Test
    @DisplayName("title 240·content 800 UTF-16 단위는 자르지 않는다(경계)")
    void promptKeepsExactLimit() {
      // given
      String title240 = "t".repeat(240);
      String content800 = "c".repeat(800);
      List<Candidate> exact =
          List.of(new Candidate(unit("u1").titled(title240).content(content800).build(), 0.5));

      // when
      String prompt = r.buildPrompt("q", exact);

      // then
      assertThat(prompt).contains(title240 + "\"").contains(content800 + "\"");
    }

    @Test
    @DisplayName("title 240·content 800 UTF-16 단위를 넘는 부분은 잘린다")
    void promptCutsOverLimit() {
      // given
      String title240 = "t".repeat(240);
      String content800 = "c".repeat(800);
      List<Candidate> over =
          List.of(
              new Candidate(
                  unit("u1").titled(title240 + "X").content(content800 + "Y").build(), 0.5));

      // when
      String prompt = r.buildPrompt("q", over);

      // then
      assertThat(prompt)
          .contains(title240 + "\"")
          .contains(content800 + "\"")
          .doesNotContain("X")
          .doesNotContain("Y");
    }

    @Test
    @DisplayName("생성기 maxTokens는 max(100, 후보 수 × 5)다")
    void maxTokensScalesWithHeadSize() {
      // given
      List<Integer> maxTokens = new ArrayList<>();

      // when: 후보 3개(→ 100)와 21개(→ 105)
      new LlmReranker(recordingMaxTokens(maxTokens), HEAD, JSON).rerank("q", candidates(3));
      new LlmReranker(recordingMaxTokens(maxTokens), HEAD, JSON).rerank("q", candidates(21));

      // then
      assertThat(maxTokens).containsExactly(100, 105);
    }
  }

  @Nested
  @DisplayName("topK로 재정렬기를 만들면")
  class TopK {
    @Test
    @DisplayName("topK 0은 거부된다")
    void topKMustBePositive() {
      // when·then
      assertThatThrownBy(() -> new LlmReranker(answering("1", new AtomicInteger()), 0, JSON))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("topK 1은 허용된다(경계)")
    void topKOneIsAccepted() {
      // when·then
      assertThatCode(() -> new LlmReranker(answering("1", new AtomicInteger()), 1, JSON))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("응답의 선택을 파싱하면")
  class ParseSelection {
    /** 쉼표로 적은 기대 순서("0,1,2"). */
    static List<Integer> indexes(String csv) {
      return Arrays.stream(csv.split(",")).map(Integer::valueOf).toList();
    }

    @ParameterizedTest(name = "\"{0}\" / 후보 {1} → {2}")
    @CsvSource(
        value = {"3|3|2", "1,2,3|3|0,1,2"},
        delimiter = '|')
    @DisplayName("번호·개수 경계 안의 선택은 0부터 센 순서다")
    void parsesSelectionWithinBounds(String response, int count, String expected) {
      // when: 번호는 1..후보 수, 개수는 후보 수까지
      List<Integer> order = LlmReranker.parseSelection(response, count);

      // then
      assertThat(order).containsExactlyElementsOf(indexes(expected));
    }

    @ParameterizedTest(name = "\"{0}\" / 후보 {1}")
    @CsvSource(
        value = {"4|3", "1,2,3,1|3", "0|3", "99999999999999999999|3"},
        delimiter = '|')
    @DisplayName("번호·개수 경계를 벗어난 선택은 무효(null)다")
    void rejectsSelectionOutOfBounds(String response, int count) {
      // when: 후보 수 + 1·개수 초과·0·long 범위를 넘는 숫자
      List<Integer> order = LlmReranker.parseSelection(response, count);

      // then
      assertThat(order).isNull();
    }
  }
}
