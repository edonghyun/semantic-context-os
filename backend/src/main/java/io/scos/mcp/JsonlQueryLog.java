package io.scos.mcp;

import io.scos.engine.support.text.JsJson;
import io.scos.mcp.wire.Wire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 질의 로그 JSONL(TS mcp-server JsonlQueryLogWriter + SearchToolHandler.logQuery, 환경변수
 * `SCOS_QUERY_LOG`): 검색 한 번에 {@code {"ts","query","topDocumentIds","topScore"}} 한 줄을 덧붙인다.
 * topDocumentIds는 응답 hits 앞 5개의 문서 id(중복 그대로), topScore는 첫 hit의 점수(응답과 같은 셋째 자리 반올림, 없으면 0). 직렬화는
 * TS `JSON.stringify`와 바이트 동등({@link JsJson}). 쓰기 실패는 무해하다 — 검색 응답을 막지 않는다(TS와 같음).
 */
public final class JsonlQueryLog implements QueryLog {
  static final int TOP_DOCUMENT_IDS_LIMIT = 5;
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final Path path;
  private final Clock clock;

  public JsonlQueryLog(Path path, Clock clock) {
    this.path = path;
    this.clock = clock;
  }

  @Override
  public synchronized void append(String query, List<Wire.SearchHit> hits) {
    try {
      Files.writeString(
          path,
          line(query, hits) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException | RuntimeException e) {
      // 로그 실패는 무해 — 서버 동작에 영향을 주지 않는다(TS JsonlQueryLogWriter)
    }
  }

  private String line(String query, List<Wire.SearchHit> hits) {
    String topDocumentIds =
        hits.stream()
            .limit(TOP_DOCUMENT_IDS_LIMIT)
            .map(h -> JsJson.string(h.documentId()))
            .collect(Collectors.joining(",", "[", "]"));
    double topScore = hits.isEmpty() ? 0 : hits.getFirst().score();
    return "{\"ts\":"
        + JsJson.string(ISO_MILLIS.format(clock.instant()))
        + ",\"query\":"
        + JsJson.string(query)
        + ",\"topDocumentIds\":"
        + topDocumentIds
        + ",\"topScore\":"
        + JsJson.number(topScore)
        + "}";
  }
}
