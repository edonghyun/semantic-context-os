package io.scos.harness.compare;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.scos.harness.fixture.FixtureSpec.ComparePolicy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MCP 응답 비교는 표기 차이를 넘기고 의미 차이만 보고한다(ADR-0010)")
class ResponseComparatorTest {
  private static final ObjectMapper M = new ObjectMapper();
  private final ResponseComparator cmp =
      new ResponseComparator(ResponseComparator.Policy.standard());

  private static JsonNode tool(String json) throws Exception {
    return M.readTree(
        "{\"content\":[{\"type\":\"text\",\"text\":" + M.writeValueAsString(json) + "}]}");
  }

  static final String TIES =
      "{\"hits\":[{\"documentId\":\"a\",\"score\":0.5},{\"documentId\":\"b\",\"score\":0.016},{\"documentId\":\"c\",\"score\":0.016},{\"documentId\":\"d\",\"score\":0.01}]}";
  static final String TIE_SWAPPED =
      "{\"hits\":[{\"documentId\":\"a\",\"score\":0.5},{\"documentId\":\"c\",\"score\":0.016},{\"documentId\":\"b\",\"score\":0.016},{\"documentId\":\"d\",\"score\":0.01}]}";
  static final String OUTSIDE_TIE_SWAPPED =
      "{\"hits\":[{\"documentId\":\"a\",\"score\":0.5},{\"documentId\":\"d\",\"score\":0.01},{\"documentId\":\"b\",\"score\":0.016},{\"documentId\":\"c\",\"score\":0.016}]}";
  static final String SAME_DOC_TIE =
      "{\"hits\":[{\"documentId\":\"d\",\"title\":\"연결 항목 1\",\"score\":0.031},{\"documentId\":\"d\",\"title\":\"연결 항목 2\",\"score\":0.031}]}";

  static final String RANKED =
      "{\"hits\":[{\"documentId\":\"a\",\"score\":0.033},{\"documentId\":\"b\",\"score\":0.016},{\"documentId\":\"c\",\"score\":0.015}],\"freshness\":{\"stale\":false}}";
  static final String RANKED_SHIFTED =
      "{\"hits\":[{\"documentId\":\"b\",\"score\":0.016},{\"documentId\":\"a\",\"score\":0.033},{\"documentId\":\"c\",\"score\":0.016}],\"freshness\":{\"stale\":false}}";

  static final String UNITS =
      "{\"units\":[{\"title\":\"a\",\"content\":\"A\"},{\"title\":\"b\",\"content\":\"B\"}],\"tokensUsed\":10,\"truncated\":false,\"freshness\":{\"stale\":false}}";
  static final String UNITS_REORDERED =
      "{\"units\":[{\"title\":\"b\",\"content\":\"B\"},{\"title\":\"a\",\"content\":\"A\"}],\"tokensUsed\":10,\"truncated\":false,\"freshness\":{\"stale\":false}}";

  static final String SCORE_HALF = "{\"hits\":[{\"documentId\":\"a\",\"score\":0.5}]}";

  static ComparePolicy rankedList(int maxRankShift, double tolerance) {
    return new ComparePolicy("ranked-list", maxRankShift, tolerance, List.of(), "r");
  }

  static ComparePolicy unitMultiset(String... exactFields) {
    return new ComparePolicy("unit-multiset", 0, 0, List.of(exactFields), "r");
  }

  @Nested
  @DisplayName("표준 비교로 보면")
  class Standard {
    @Test
    @DisplayName("isError:false는 생략과 같다(MCP 선택 필드) — TS 생략 vs Java SDK false")
    void isErrorFalseEqualsAbsent() throws Exception {
      // given
      JsonNode absent = tool("{\"hits\":[]}");
      JsonNode falseFlag = ((ObjectNode) absent.deepCopy()).put("isError", false);

      // when·then
      assertThat(cmp.compareToolResult(absent, falseFlag)).isEmpty();
    }

    @Test
    @DisplayName("키 순서와 정수·실수 표기가 달라도 같다")
    void keyOrderDoesNotMatter() throws Exception {
      // when·then
      assertThat(
              cmp.compareToolResult(
                  tool("{\"a\":1,\"b\":{\"c\":[1,2]}}"), tool("{\"b\":{\"c\":[1,2]},\"a\":1.0}")))
          .isEmpty();
    }

    @Test
    @DisplayName("생략 필드와 null의 차이는 그 경로로 보고된다")
    void absentIsNotNull() throws Exception {
      // when·then
      assertThat(cmp.compareToolResult(tool("{\"a\":1}"), tool("{\"a\":1,\"x\":null}")))
          .extracting(ResponseComparator.Difference::path)
          .containsExactly("$.content[0].json.x");
    }

    @Test
    @DisplayName("점수는 상대 오차 1e-6 안이면 같다")
    void scoreWithinToleranceIsEqual() throws Exception {
      // when·then
      assertThat(
              cmp.compareToolResult(
                  tool(SCORE_HALF),
                  tool("{\"hits\":[{\"documentId\":\"a\",\"score\":0.50000004}]}")))
          .isEmpty();
    }

    @Test
    @DisplayName("점수가 상대 오차 1e-6 밖이면 다르다")
    void scoreBeyondToleranceDiffers() throws Exception {
      // when·then
      assertThat(
              cmp.compareToolResult(
                  tool(SCORE_HALF), tool("{\"hits\":[{\"documentId\":\"a\",\"score\":0.501}]}")))
          .hasSize(1);
    }

    @Test
    @DisplayName("점수가 아닌 숫자는 값이 같아야 한다")
    void nonScoreNumbersMustMatch() throws Exception {
      // when·then
      assertThat(cmp.compareToolResult(tool("{\"tokensUsed\":120}"), tool("{\"tokensUsed\":121}")))
          .hasSize(1);
    }
  }

  @Nested
  @DisplayName("검색 결과에 같은 점수의 동점군이 있으면")
  class TieGroups {
    @Test
    @DisplayName("동점군 안에서는 순서가 바뀌어도 같다")
    void orderWithinTieGroupIsFree() throws Exception {
      // when·then
      assertThat(cmp.compareToolResult(tool(TIES), tool(TIE_SWAPPED))).isEmpty();
    }

    @Test
    @DisplayName("동점군 밖 순서 차이는 다르다(순위를 임의로 정리하지 않는다)")
    void orderOutsideTieGroupMatters() throws Exception {
      // when
      List<ResponseComparator.Difference> d =
          cmp.compareToolResult(tool(TIES), tool(OUTSIDE_TIE_SWAPPED));

      // then
      assertThat(d).isNotEmpty();
      assertThat(d.get(0).message()).contains("tie group members");
    }

    @Test
    @DisplayName("같은 documentId의 두 유닛이 한 동점군에서 자리를 바꿔도 같다")
    void sameDocumentTwiceInTieGroup() throws Exception {
      // given
      String swapped =
          "{\"hits\":[{\"documentId\":\"d\",\"title\":\"연결 항목 2\",\"score\":0.031},{\"documentId\":\"d\",\"title\":\"연결 항목 1\",\"score\":0.031}]}";

      // when·then
      assertThat(cmp.compareToolResult(tool(SAME_DOC_TIE), tool(SAME_DOC_TIE))).isEmpty();
      assertThat(cmp.compareToolResult(tool(SAME_DOC_TIE), tool(swapped))).isEmpty();
    }

    @Test
    @DisplayName("같은 documentId의 유닛은 제목으로 구분해 다른 유닛이면 다르다")
    void sameDocumentDistinguishedByTitle() throws Exception {
      // given
      String changed =
          "{\"hits\":[{\"documentId\":\"d\",\"title\":\"연결 항목 1\",\"score\":0.031},{\"documentId\":\"d\",\"title\":\"연결 항목 3\",\"score\":0.031}]}";

      // when·then
      assertThat(cmp.compareToolResult(tool(SAME_DOC_TIE), tool(changed))).hasSize(1);
    }
  }

  @Nested
  @DisplayName("ranked-list 정책으로 비교하면")
  class RankedListPolicy {
    @Test
    @DisplayName("같은 집합의 순위·점수 차가 한도 안이면 통과한다")
    void rankedListWithinShiftPasses() throws Exception {
      // when·then
      assertThat(cmp.compareWithPolicy(tool(RANKED), tool(RANKED_SHIFTED), rankedList(2, 0.002)))
          .isEmpty();
    }

    @Test
    @DisplayName("maxRankShift 0이면 순위 차 1도 실패다")
    void rankedListZeroShiftIsStrict() throws Exception {
      // when·then
      assertThat(cmp.compareWithPolicy(tool(RANKED), tool(RANKED_SHIFTED), rankedList(0, 0.002)))
          .isNotEmpty();
    }

    @Test
    @DisplayName("집합이 다르면(누락·추가) 실패다")
    void rankedListSetDifferenceFails() throws Exception {
      // given
      String tooFar =
          "{\"hits\":[{\"documentId\":\"b\",\"score\":0.016},{\"documentId\":\"c\",\"score\":0.015},{\"documentId\":\"d\",\"score\":0.01}],\"freshness\":{\"stale\":false}}";

      // when·then
      assertThat(cmp.compareWithPolicy(tool(RANKED), tool(tooFar), rankedList(2, 0.002)))
          .isNotEmpty();
    }

    @Test
    @DisplayName("점수 차가 tol을 넘으면 실패다")
    void rankedListScoreBeyondToleranceFails() throws Exception {
      // given
      String scoreOff = RANKED.replace("\"score\":0.033", "\"score\":0.040");

      // when·then
      assertThat(cmp.compareWithPolicy(tool(RANKED), tool(scoreOff), rankedList(2, 0.002)))
          .hasSize(1);
    }

    @Test
    @DisplayName("hits 밖 필드(freshness)의 차이는 그대로 실패다")
    void rankedListOtherFieldDifferenceFails() throws Exception {
      // given
      String freshOff = RANKED.replace("\"stale\":false", "\"stale\":true");

      // when·then
      assertThat(cmp.compareWithPolicy(tool(RANKED), tool(freshOff), rankedList(2, 0.002)))
          .hasSize(1);
    }

    @Test
    @DisplayName("십진으로 잰 점수 차가 허용 오차와 같으면 통과한다")
    void rankedListToleranceIsDecimalInside() throws Exception {
      // given: 0.016과 0.015는 ±0.001 안(이진 부동소수로는 밖일 수 있다)
      ComparePolicy decimal = rankedList(0, 0.001);

      // when·then
      assertThat(
              cmp.compareWithPolicy(
                  tool(RANKED),
                  tool(RANKED.replace("\"score\":0.015", "\"score\":0.016")),
                  decimal))
          .isEmpty();
    }

    @Test
    @DisplayName("십진으로 잰 점수 차가 허용 오차를 넘으면 실패다")
    void rankedListToleranceIsDecimalOutside() throws Exception {
      // given: 0.0161은 ±0.001 밖
      ComparePolicy decimal = rankedList(0, 0.001);

      // when·then
      assertThat(
              cmp.compareWithPolicy(
                  tool(RANKED),
                  tool(RANKED.replace("\"score\":0.015", "\"score\":0.0161")),
                  decimal))
          .hasSize(1);
    }
  }

  @Nested
  @DisplayName("unit-multiset 정책으로 비교하면")
  class UnitMultisetPolicy {
    @Test
    @DisplayName("units 순서만 다르면 통과한다")
    void unitMultisetIgnoresOrder() throws Exception {
      // when·then
      assertThat(
              cmp.compareWithPolicy(
                  tool(UNITS),
                  tool(UNITS_REORDERED),
                  unitMultiset("tokensUsed", "truncated", "freshness")))
          .isEmpty();
    }

    @Test
    @DisplayName("유닛이 바뀌면(누락·추가) 실패다")
    void unitMultisetDifferenceFails() throws Exception {
      // given
      String dropped =
          "{\"units\":[{\"title\":\"b\",\"content\":\"B\"},{\"title\":\"x\",\"content\":\"X\"}],\"tokensUsed\":10,\"truncated\":false,\"freshness\":{\"stale\":false}}";

      // when·then
      assertThat(
              cmp.compareWithPolicy(
                  tool(UNITS), tool(dropped), unitMultiset("tokensUsed", "truncated", "freshness")))
          .isNotEmpty();
    }

    @Test
    @DisplayName("exact 필드(tokensUsed)의 차이는 실패다")
    void unitMultisetExactFieldDifferenceFails() throws Exception {
      // given
      String tokensOff = UNITS.replace("\"tokensUsed\":10", "\"tokensUsed\":11");

      // when·then
      assertThat(
              cmp.compareWithPolicy(
                  tool(UNITS),
                  tool(tokensOff),
                  unitMultiset("tokensUsed", "truncated", "freshness")))
          .hasSize(1);
    }

    @Test
    @DisplayName("선언하지 않은 필드의 차이는 실패다")
    void unitMultisetUndeclaredFieldFails() throws Exception {
      // when·then
      assertThat(
              cmp.compareWithPolicy(tool(UNITS), tool(UNITS_REORDERED), unitMultiset("tokensUsed")))
          .isNotEmpty();
    }
  }

  @Nested
  @DisplayName("isError 응답을 비교하면 문구가 아니라 오류 종류를 본다(규칙 3)")
  class ErrorKinds {
    final JsonNode notFoundX = isError("문서를 찾을 수 없음: x");
    final JsonNode notFoundY = isError("문서를 찾을 수 없음: y");
    final JsonNode zod =
        isError(
            "MCP error -32602: Input validation error: Invalid arguments for tool read_document: Too big");
    final JsonNode sdk =
        isError("Tool (read_document) input validation failed: [/limit: 최대값은 100이어야 합니다.]");
    final JsonNode unknown = isError("MCP error -32602: Tool no_such_tool not found");

    @Test
    @DisplayName("not-found끼리는 대상 id가 달라도 같다")
    void notFoundIgnoresMessage() {
      // when·then
      assertThat(cmp.compareToolResult(notFoundX, notFoundY)).isEmpty();
    }

    @Test
    @DisplayName("validation끼리는 구현의 문구·로케일이 달라도 같다")
    void validationIgnoresWording() {
      // when·then
      assertThat(cmp.compareToolResult(zod, sdk)).isEmpty();
    }

    @Test
    @DisplayName("종류가 다른 오류는 다르다")
    void differentKindsDiffer() {
      // when·then
      assertThat(cmp.compareToolResult(zod, notFoundX)).hasSize(1);
      assertThat(cmp.compareToolResult(unknown, zod)).hasSize(1);
    }

    @Test
    @DisplayName("정상 응답과 오류 응답은 다르다")
    void okDiffersFromError() throws Exception {
      // when·then
      assertThat(cmp.compareToolResult(tool("{\"hits\":[]}"), notFoundX)).hasSize(1);
    }

    @Test
    @DisplayName("없는 도구 오류의 종류는 unknown-tool이다")
    void unknownToolKind() {
      // when·then
      assertThat(ResponseComparator.errorKind(unknown)).isEqualTo("unknown-tool");
    }
  }

  static JsonNode isError(String text) {
    try {
      return M.readTree(
          "{\"content\":[{\"type\":\"text\",\"text\":"
              + M.writeValueAsString(text)
              + "}],\"isError\":true}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
