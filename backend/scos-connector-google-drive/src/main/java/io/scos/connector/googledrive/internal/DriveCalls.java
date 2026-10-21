package io.scos.connector.googledrive.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drive run 하나의 공급자 읽기와 원본 만들기(TS DriveCollection의 json·upsert·remove): 읽기는 실행기의 시도마다 세션으로 부른다. 선택
 * 본문(본문· 댓글·파일·범위)이면 403·404(요청 제한이 아닌 것)와 본문 상한 초과를 {@link UnavailableContent}로 바꿔 수집 제한으로 남게 한다.
 */
final class DriveCalls {
  /** 원본 버전이 없는 바이너리의 sourceVersion 대체값(TS Date#toISOString — 밀리초 셋). */
  static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private static final Set<Integer> UNAVAILABLE = Set.of(403, 404);

  /** 읽기 하나가 필수인지(실패가 run 실패) 선택인지(실패가 수집 제한). */
  enum Availability {
    REQUIRED,
    OPTIONAL
  }

  private final CollectionInput input;
  private final GoogleDriveSession session;
  private final GoogleDriveConfiguration config;
  private final ObjectMapper json;

  DriveCalls(
      CollectionInput input,
      GoogleDriveSession session,
      GoogleDriveConfiguration config,
      ObjectMapper json) {
    this.input = input;
    this.session = session;
    this.config = config;
    this.json = json;
  }

  CollectionInput input() {
    return input;
  }

  ObjectMapper mapper() {
    return json;
  }

  GoogleDriveConfiguration config() {
    return config;
  }

  GoogleDriveSession session() {
    return session;
  }

  /** JSON 객체 응답 하나(operation `google-drive.<서비스>.read`). */
  ObjectNode json(GoogleService service, String path, Availability availability) {
    return (ObjectNode)
        input
            .calls()
            .execute(
                "google-drive." + service.name().toLowerCase(Locale.ROOT) + ".read",
                timeout -> session.json(service, path, timeout),
                e -> {
                  if (e instanceof GoogleContentTooLargeException tooLarge) {
                    if (availability == Availability.OPTIONAL)
                      throw new UnavailableContent(tooLarge.getMessage());
                    throw tooLarge;
                  }
                  if (availability == Availability.OPTIONAL) unavailableUnlessRateLimited(e);
                  return GoogleErrors.classify(e);
                },
                input.cancel());
  }

  /** 403·404이고 요청 제한이 아니면 읽을 수 없는 본문이다. */
  static void unavailableUnlessRateLimited(Exception e) {
    if (e instanceof GoogleApiException api
        && UNAVAILABLE.contains(api.status())
        && !api.rateLimited()) throw new UnavailableContent(api.getMessage());
  }

  /**
   * 원본 upsert(TS upsert): 외부 id `<종류>:<id>`, 버전은 TS `JSON.stringify(값)`의 sha256, 시각은 file의
   * createdTime·modifiedTime, metadata는 webViewLink(없으면 Drive 주소)·계정(시트 범위는 fileId도). 캡처 상한을 넘으면
   * {@code RawContentTooLargeException}.
   */
  RawMutation upsert(RawKind kind, String id, ObjectNode value, Instant observedAt, JsonNode file) {
    String stringified = JsJson.stringify(value);
    ObjectNode metadata = json.createObjectNode();
    JsonNode link = file.get("webViewLink");
    metadata.put(
        "url",
        link != null && link.isString()
            ? link.asString()
            : "https://drive.google.com/"
                + (kind == RawKind.DRIVE ? "drive/folders" : "file/d")
                + "/"
                + GoogleQuery.component(id));
    metadata.put("accountEmail", config.accountEmail());
    if (kind == RawKind.SHEET) metadata.put("fileId", JsValues.string(file.get("id")));
    RawItem item =
        new RawItem(
            kind.externalId(id),
            kind.resourceType(),
            Text.sha256Hex(stringified),
            Optional.empty(),
            text(file.get("createdTime")),
            text(file.get("modifiedTime")),
            observedAt,
            JsJson.stringify(metadata),
            Optional.of(input.content().json("application/json", stringified)));
    return new RawMutation.Upsert(item);
  }

  static RawMutation remove(String externalId, Instant observedAt) {
    return new RawMutation.Delete(
        externalId, Optional.empty(), Optional.empty(), Optional.empty(), observedAt);
  }

  /** 수집 제한 하나(unsupported_item, TS 메시지). locator는 JSON. */
  static RejectedItem rejection(JsonNode locator, String message, Instant observedAt) {
    return new RejectedItem(
        RejectedItem.FailureKind.UNSUPPORTED_ITEM,
        message,
        Optional.of(JsJson.stringify(locator)),
        observedAt,
        Optional.empty());
  }

  private static Optional<String> text(JsonNode value) {
    return value != null && value.isString() ? Optional.of(value.asString()) : Optional.empty();
  }
}
