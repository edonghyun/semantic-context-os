package io.scos.harness.evaluation.baseline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.mcp.JsonRpcStdioClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기준선용 판정셋. 판정셋 v1에 질의별 kind("why"|"resume")와 baseline.terms(1–3개, 필수)·baseline.gitTerms(선택, 없으면
 * terms)를 더한 형식이다. 모르는 필드는 읽지 않는다.
 */
public final class BaselinePack {
  private static final List<String> KINDS = List.of("why", "resume");
  private static final int MAX_TERMS = 3;

  private BaselinePack() {}

  /** 질의 하나. kind는 판정셋에 없으면 null이다. */
  public record Query(
      String query, String kind, List<String> terms, List<String> gitTerms, Set<String> expected) {}

  public static List<Query> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("--pack file not found");
    JsonNode root;
    try {
      root = JsonRpcStdioClient.mapper().readTree(Files.readString(file));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("pack is not JSON");
    }
    JsonNode queries = root.path("queries");
    if (!queries.isArray() || queries.isEmpty())
      throw new IllegalArgumentException("pack needs a non-empty queries array");
    List<Query> result = new ArrayList<>();
    for (int i = 0; i < queries.size(); i++) result.add(query(i, queries.get(i)));
    return result;
  }

  private static Query query(int index, JsonNode node) {
    String text = node.path("query").asText("");
    if (!node.path("query").isTextual() || text.isBlank())
      throw new IllegalArgumentException("query " + index + ": query must be a non-empty string");
    JsonNode kind = node.path("kind");
    if (!kind.isMissingNode() && !(kind.isTextual() && KINDS.contains(kind.asText())))
      throw new IllegalArgumentException("query " + index + ": kind must be why or resume");
    List<String> terms = terms(index, node.path("baseline").path("terms"), "baseline.terms");
    JsonNode gitNode = node.path("baseline").path("gitTerms");
    List<String> gitTerms =
        gitNode.isMissingNode() ? terms : terms(index, gitNode, "baseline.gitTerms");
    return new Query(
        text, kind.isMissingNode() ? null : kind.asText(), terms, gitTerms, expected(index, node));
  }

  private static List<String> terms(int index, JsonNode node, String field) {
    String problem = "query " + index + ": " + field + " must be 1 to " + MAX_TERMS;
    if (!node.isArray() || node.isEmpty() || node.size() > MAX_TERMS)
      throw new IllegalArgumentException(problem + " non-empty strings");
    List<String> terms = new ArrayList<>();
    for (JsonNode term : node) {
      if (!term.isTextual() || term.asText().isBlank())
        throw new IllegalArgumentException(problem + " non-empty strings");
      terms.add(term.asText());
    }
    return List.copyOf(terms);
  }

  private static Set<String> expected(int index, JsonNode node) {
    JsonNode ids = node.path("expectedDocumentIds");
    if (!ids.isArray() || ids.isEmpty())
      throw new IllegalArgumentException("query " + index + " has no expectedDocumentIds");
    Set<String> expected = new LinkedHashSet<>();
    for (JsonNode id : ids) {
      if (!id.isTextual() || id.asText().isBlank())
        throw new IllegalArgumentException(
            "query " + index + " has an expectedDocumentIds entry that is not a non-empty string");
      expected.add(id.asText());
    }
    return Set.copyOf(expected);
  }
}
