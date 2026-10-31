package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("기준선 출력은 기존 JSON 문서와 같은 형식을 보존한다(C10)")
class BaselineOutputTest {
  @TempDir Path dir;

  @Test
  @DisplayName("측정하면 질의별 순위와 점수를 정확한 JSONL 문서로 쓴다")
  void writesExactQueries() throws Exception {
    // given
    var scenario = new BaselineScenario(dir);

    // when
    scenario.measure();

    // then
    assertThat(scenario.queries())
        .isEqualTo(
            """
            {"index":0,"kind":"why","expected":["transcript/d/s1"],"rg":["transcript/d/s1","transcript/d/s2"],"git":[],"recall":1.0,"rr":1.0,"ndcg":1.0,"rgScores":{"recall":1.0,"mrr":1.0,"ndcg":1.0},"gitScores":{"recall":0.0,"mrr":0.0,"ndcg":0.0}}
            {"index":1,"kind":"resume","expected":["memory/d/m.md"],"rg":["memory/d/m.md","transcript/d/s2"],"git":[],"recall":1.0,"rr":1.0,"ndcg":1.0,"rgScores":{"recall":1.0,"mrr":1.0,"ndcg":1.0},"gitScores":{"recall":0.0,"mrr":0.0,"ndcg":0.0}}
            """);
  }

  @Test
  @DisplayName("측정하면 평균과 출처별 점수를 정확한 JSON 문서로 쓴다")
  void writesExactSummary() throws Exception {
    // given
    var scenario = new BaselineScenario(dir);

    // when
    scenario.measure();

    // then
    assertThat(scenario.summary())
        .isEqualTo(
            """
            {
              "queries" : 2,
              "k" : 5,
              "recall" : 1.0,
              "mrr" : 1.0,
              "ndcg" : 1.0,
              "bySource" : {
                "rg" : {
                  "recall" : 1.0,
                  "mrr" : 1.0,
                  "ndcg" : 1.0
                },
                "git" : {
                  "recall" : 0.0,
                  "mrr" : 0.0,
                  "ndcg" : 0.0
                }
              }
            }""");
  }

  @Test
  @DisplayName("원문 포함을 요청하면 질의 필드를 kind 다음에 직렬화한다")
  void writesExactQueriesWithText() throws Exception {
    // given
    var scenario = new BaselineScenario(dir);
    scenario.includeQueryText();

    // when
    scenario.measure();

    // then
    assertThat(scenario.queries())
        .isEqualTo(
            """
            {"index":0,"kind":"why","query":"what was the alpha plan","expected":["transcript/d/s1"],"rg":["transcript/d/s1","transcript/d/s2"],"git":[],"recall":1.0,"rr":1.0,"ndcg":1.0,"rgScores":{"recall":1.0,"mrr":1.0,"ndcg":1.0},"gitScores":{"recall":0.0,"mrr":0.0,"ndcg":0.0}}
            {"index":1,"kind":"resume","query":"where did we leave zeta","expected":["memory/d/m.md"],"rg":["memory/d/m.md","transcript/d/s2"],"git":[],"recall":1.0,"rr":1.0,"ndcg":1.0,"rgScores":{"recall":1.0,"mrr":1.0,"ndcg":1.0},"gitScores":{"recall":0.0,"mrr":0.0,"ndcg":0.0}}
            """);
  }

  @Test
  @DisplayName("kind와 매치가 없으면 선택 필드를 생략하고 정답 id는 정렬한다")
  void omitsAbsentKindAndSortsExpectedIds() throws Exception {
    // given
    var scenario = new BaselineScenario(dir);
    Path pack = dir.resolve("pack.json");
    Files.writeString(
        pack,
        """
        {"name":"empty","version":1,"queries":[{"query":"q",
         "expectedDocumentIds":["z","a"],"baseline":{"terms":["absent-term"]}}]}
        """);
    scenario.givenPack(pack.toString());

    // when
    scenario.measure();

    // then
    assertThat(scenario.queries())
        .isEqualTo(
            """
            {"index":0,"expected":["a","z"],"rg":[],"git":[],"recall":0.0,"rr":0.0,"ndcg":0.0,"rgScores":{"recall":0.0,"mrr":0.0,"ndcg":0.0},"gitScores":{"recall":0.0,"mrr":0.0,"ndcg":0.0}}
            """);
  }
}
