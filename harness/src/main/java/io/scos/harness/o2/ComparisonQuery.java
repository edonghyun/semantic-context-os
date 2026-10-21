package io.scos.harness.o2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 비교할 질의 하나. 정답 문서(expectedDocumentIds)는 판정셋(judgment pack)에만 있다. 입력은 두 형식(형식 오류는 줄 번호와 함께 실패하고 질의
 * 원문은 싣지 않는다):
 *
 * <ul>
 *   <li>질의 로그 JSONL(SCOS_QUERY_LOG, TS·Java 같은 형식): 줄마다 {@code
 *       {"ts","query","topDocumentIds","topScore"}} — query만 쓴다. 빈 줄은 건너뛴다.
 *   <li>판정셋 JSON(TS bench PackLoader 형식): {@code {"name","version","docs","queries":[{"query",
 *       "expectedDocumentIds",…}]}}.
 * </ul>
 */
public record ComparisonQuery(
    String text, Optional<List<String>> expectedDocumentIds, Optional<Instant> loggedAt) {
  static final ObjectMapper JSON = new ObjectMapper();

  /** 기록 시각이 없는 질의(pack·시험). */
  public ComparisonQuery(String text, Optional<List<String>> expectedDocumentIds) {
    this(text, expectedDocumentIds, Optional.empty());
  }

  /**
   * 기록 시각(질의 로그 ts)이 [since, until) 안인 질의만 메모리에서 거른다 — 원문을 새 파일로 자르지 않는다(사용자 승인 조건). 기간을 줬는데 시각이 없는
   * 질의(pack)는 사용법 오류다.
   */
  public static List<ComparisonQuery> between(
      List<ComparisonQuery> queries, Optional<Instant> since, Optional<Instant> until) {
    if (since.isEmpty() && until.isEmpty()) return queries;
    List<ComparisonQuery> out = new ArrayList<>();
    for (ComparisonQuery q : queries) {
      Instant at =
          q.loggedAt()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException("--since/--until need query-log input with ts"));
      if (since.map(at::isBefore).orElse(false)) continue;
      if (until.map(u -> !at.isBefore(u)).orElse(false)) continue;
      out.add(q);
    }
    return out;
  }

  public enum Format {
    AUTO,
    QUERY_LOG,
    PACK
  }

  /** 질의 원문 대신 보고서에 남기는 식별자: sha256 앞 16자(hex). */
  public String hash() {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static List<ComparisonQuery> load(Path file, Format format) throws IOException {
    String content = Files.readString(file);
    Format resolved = format == Format.AUTO ? detect(content) : format;
    return resolved == Format.PACK ? pack(content) : queryLog(content);
  }

  /** 문서 전체가 queries 배열을 가진 JSON 객체 하나면 pack, 아니면 JSONL. */
  static Format detect(String content) {
    try {
      JsonNode whole = JSON.readTree(content);
      if (whole != null && whole.isObject() && whole.has("queries")) return Format.PACK;
    } catch (JsonProcessingException e) {
      // 여러 줄 JSONL은 문서 하나로 읽히지 않는다
    }
    return Format.QUERY_LOG;
  }

  static List<ComparisonQuery> queryLog(String content) {
    List<ComparisonQuery> out = new ArrayList<>();
    String[] lines = content.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty()) continue;
      JsonNode node;
      try {
        node = JSON.readTree(line);
      } catch (JsonProcessingException e) {
        throw invalid("line " + (i + 1) + ": not JSON");
      }
      if (node == null || !node.path("query").isTextual() || node.get("query").asText().isBlank())
        throw invalid("line " + (i + 1) + ": no non-empty \"query\" string");
      Optional<Instant> at = Optional.empty();
      if (node.path("ts").isTextual()) {
        try {
          at = Optional.of(Instant.parse(node.get("ts").asText()));
        } catch (DateTimeParseException e) {
          throw invalid("line " + (i + 1) + ": ts is not an ISO instant");
        }
      }
      out.add(new ComparisonQuery(node.get("query").asText(), Optional.empty(), at));
    }
    if (out.isEmpty()) throw invalid("no queries");
    return out;
  }

  static List<ComparisonQuery> pack(String content) {
    JsonNode root;
    try {
      root = JSON.readTree(content);
    } catch (JsonProcessingException e) {
      throw invalid("pack is not JSON");
    }
    JsonNode queries = root.path("queries");
    if (!queries.isArray() || queries.isEmpty()) throw invalid("pack has no queries");
    List<ComparisonQuery> out = new ArrayList<>();
    for (int i = 0; i < queries.size(); i++) {
      JsonNode q = queries.get(i);
      if (!q.path("query").isTextual() || q.get("query").asText().isBlank())
        throw invalid("queries[" + i + "]: no non-empty \"query\" string");
      Optional<List<String>> expected = Optional.empty();
      if (q.path("expectedDocumentIds").isArray()) {
        List<String> ids = new ArrayList<>();
        q.get("expectedDocumentIds").forEach(id -> ids.add(id.asText()));
        expected = Optional.of(List.copyOf(ids));
      }
      out.add(new ComparisonQuery(q.get("query").asText(), expected));
    }
    return out;
  }

  private static IllegalArgumentException invalid(String where) {
    return new IllegalArgumentException("invalid query input — " + where);
  }
}
