package io.scos.harness.o2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** search_context 응답 → 문서 id 순위(유닛 hits에서 문서 중복 제거). */
@DisplayName("검색 응답은 문서 순위로 읽히고 오류 응답은 실패가 된다(O2)")
class SearchHitsTest {
  static JsonNode result(String hitsJson) throws Exception {
    String text = ComparisonQuery.JSON.writeValueAsString("{\"hits\":" + hitsJson + "}");
    return ComparisonQuery.JSON.readTree(
        "{\"content\":[{\"type\":\"text\",\"text\":" + text + "}]}");
  }

  @Test
  @DisplayName("같은 문서의 유닛은 처음 나온 순서로 한 번만 센다")
  void dedupesDocuments() throws Exception {
    // when
    var hits =
        SearchHits.parse(
            result("[{\"documentId\":\"d2\"},{\"documentId\":\"d1\"},{\"documentId\":\"d2\"}]"));

    // then
    assertThat(hits.documentIds()).containsExactly("d2", "d1");
  }

  @Test
  @DisplayName("JSON-RPC 오류는 실패로 돌려준다")
  void jsonRpcErrorFails() throws Exception {
    // when
    var hits =
        SearchHits.parse(ComparisonQuery.JSON.readTree("{\"jsonrpcError\":{\"code\":-32602}}"));

    // then
    assertThat(hits.error()).contains("jsonrpc error -32602");
  }

  @Test
  @DisplayName("isError 응답은 실패로 돌려준다")
  void toolErrorFails() throws Exception {
    // when
    var hits = SearchHits.parse(ComparisonQuery.JSON.readTree("{\"isError\":true,\"content\":[]}"));

    // then
    assertThat(hits.ok()).isFalse();
  }

  @Test
  @DisplayName("hits 배열이 없으면 실패로 돌려준다")
  void missingHitsFails() throws Exception {
    // when
    var hits =
        SearchHits.parse(
            ComparisonQuery.JSON.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}"));

    // then
    assertThat(hits.error()).contains("no hits array");
  }
}
