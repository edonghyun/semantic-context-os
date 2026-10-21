package io.scos.collection.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.SourceInstanceId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 원본 카탈로그 커서(TS encodeRawItemCursor): base64url(패딩 없음)로 감싼 JSON 배열 [observedAt, 소스, externalId] —
 * 마지막 항목의 정렬 키다. observedAt은 밀리초까지면 TS toISOString과 같은 표기라 커서도 TS와 같은 바이트다. 그보다 정밀한 시각(PostgreSQL은
 * 마이크로초)은 잘라 내지 않고 그대로 싣는다 — 잘라 내면 같은 밀리초 안의 항목을 건너뛴다.
 */
public record RawCatalogCursor(Instant observedAt, SourceInstanceId source, String externalId) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public String encode() {
    String at =
        observedAt.equals(observedAt.truncatedTo(ChronoUnit.MILLIS))
            ? ISO_MILLIS.format(observedAt)
            : DateTimeFormatter.ISO_INSTANT.format(observedAt);
    String array =
        "["
            + JsJson.string(at)
            + ","
            + JsJson.string(source.value())
            + ","
            + JsJson.string(externalId)
            + "]";
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(array.getBytes(StandardCharsets.UTF_8));
  }

  /** 읽을 수 없는 커서는 IllegalArgumentException(명령은 종료 코드 1). */
  public static RawCatalogCursor decode(String cursor) {
    try {
      JsonNode array =
          JSON.readTree(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
      if (!array.isArray()
          || array.size() != 3
          || !array.get(0).isString()
          || !array.get(1).isString()
          || !array.get(2).isString()) throw invalid(cursor);
      return new RawCatalogCursor(
          Instant.parse(array.get(0).asString()),
          new SourceInstanceId(array.get(1).asString()),
          array.get(2).asString());
    } catch (JacksonException | DateTimeParseException | IllegalArgumentException e) {
      throw invalid(cursor);
    }
  }

  private static IllegalArgumentException invalid(String cursor) {
    return new IllegalArgumentException("Invalid raw catalog cursor: " + cursor);
  }
}
