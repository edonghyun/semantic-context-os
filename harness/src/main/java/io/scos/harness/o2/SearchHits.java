package io.scos.harness.o2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * search_context 응답(tools/call result) → 문서 id 순위. 응답 본문은 {@code content[0].text}의 JSON {@code
 * {"hits":[{documentId, …}]}}이다(TS·Java 같은 계약). hits는 유닛 단위라 같은 문서가 여러 번 나올 수 있다 — 비교는 문서 단위라 처음 나온
 * 순서로 중복을 뺀다. JSON-RPC 오류·isError·형식 위반은 error로 돌려준다(메시지에 응답 본문을 싣지 않는다).
 */
public record SearchHits(List<String> documentIds, Optional<String> error) {
  public static SearchHits failed(String reason) {
    return new SearchHits(List.of(), Optional.of(reason));
  }

  public boolean ok() {
    return error.isEmpty();
  }

  public static SearchHits parse(JsonNode result) {
    if (result == null) return failed("no result");
    if (result.has("jsonrpcError"))
      return failed("jsonrpc error " + result.get("jsonrpcError").path("code").asText());
    if (result.path("isError").asBoolean(false)) return failed("tool error");
    JsonNode text = result.path("content").path(0).path("text");
    if (!text.isTextual()) return failed("no text content");
    JsonNode body;
    try {
      body = ComparisonQuery.JSON.readTree(text.asText());
    } catch (JsonProcessingException e) {
      return failed("content is not JSON");
    }
    JsonNode hits = body.path("hits");
    if (!hits.isArray()) return failed("no hits array");
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (JsonNode hit : hits) {
      if (!hit.path("documentId").isTextual()) return failed("hit without documentId");
      ids.add(hit.get("documentId").asText());
    }
    return new SearchHits(List.copyOf(new ArrayList<>(ids)), Optional.empty());
  }
}
