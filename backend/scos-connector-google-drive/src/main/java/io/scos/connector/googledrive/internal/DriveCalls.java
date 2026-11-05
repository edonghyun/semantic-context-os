package io.scos.connector.googledrive.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drive run 하나의 공급자 읽기와 원본 만들기: 읽기는 실행기의 시도마다 세션으로 부른다. 선택 본문(본문· 댓글·파일·범위)이면 403·404(요청 제한이 아닌 것)와
 * 본문 상한 초과를 {@link UnavailableContent}로 바꿔 수집 제한으로 남게 한다.
 */
final class DriveCalls {
  /** 원본 버전이 없는 바이너리의 sourceVersion 대체값(Date#toISOString 형식 — 밀리초 셋). */
  static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private static final Set<Integer> UNAVAILABLE = Set.of(403, 404);
  private static final int BAD_GATEWAY = 502;

  /** 읽기 하나가 필수인지(실패가 run 실패) 선택인지(실패가 수집 제한). */
  enum Availability {
    REQUIRED,
    ACL,
    OPTIONAL
  }

  private final CallExecutor calls;
  private final CancellationToken cancel;
  private final @Nullable ContentCapture content;
  private final GoogleDriveSession session;
  private final GoogleDriveConfiguration config;
  private final ObjectMapper json;

  DriveCalls(
      CollectionInput input,
      GoogleDriveSession session,
      GoogleDriveConfiguration config,
      ObjectMapper json) {
    this(input.calls(), input.cancel(), input.content(), session, config, json);
  }

  /** 접근 수집은 본문 캡처 없이 같은 세션·재시도 정책만 소비한다. */
  DriveCalls(
      CallExecutor calls,
      CancellationToken cancel,
      GoogleDriveSession session,
      GoogleDriveConfiguration config,
      ObjectMapper json) {
    this(calls, cancel, null, session, config, json);
  }

  private DriveCalls(
      CallExecutor calls,
      CancellationToken cancel,
      @Nullable ContentCapture content,
      GoogleDriveSession session,
      GoogleDriveConfiguration config,
      ObjectMapper json) {
    this.calls = calls;
    this.cancel = cancel;
    this.content = content;
    this.session = session;
    this.config = config;
    this.json = json;
  }

  /** JSON 객체 응답 하나(operation `google-drive.<서비스>.read`). */
  ObjectNode json(GoogleService service, String path, Availability availability) {
    return (ObjectNode)
        calls.execute(
            "google-drive." + service.name().toLowerCase(Locale.ROOT) + ".read",
            timeout -> session.json(service, path, timeout),
            e -> classify(e, availability),
            cancel);
  }

  /**
   * 원본 바이트 하나(operation `google-drive.download`, 상한 maxBytes). 선택 본문이다. 메타데이터 크기를 알고 받은 크기가 다르면 502
   * incomplete_download(재시도).
   */
  byte[] download(String fileId, OptionalDouble expectedBytes, long maxBytes) {
    return calls.execute(
        "google-drive.download",
        timeout -> {
          byte[] bytes = session.download(fileId, timeout, maxBytes);
          if (expectedBytes.isPresent() && bytes.length != expectedBytes.getAsDouble())
            throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INCOMPLETE_DOWNLOAD);
          return bytes;
        },
        e -> classify(e, Availability.OPTIONAL),
        cancel);
  }

  /** 받은 바이트를 원본 콘텐츠로 담는다(캡처 상한을 넘으면 {@code RawContentTooLargeException}). */
  RawContent capture(String mediaType, byte[] bytes) {
    return bodyContent().bytes(mediaType, bytes);
  }

  private ContentCapture bodyContent() {
    // 본문 전용 연산은 CollectionInput 생성 경계에서만 호출한다.
    return Objects.requireNonNull(content, "Body capture is unavailable during access collection");
  }

  boolean downloadsBinaries() {
    return config.downloadBinaries();
  }

  /** 소문자로 바꾼 OAuth 계정 이메일이 설정 계정인가. */
  boolean isConfiguredAccount(String email) {
    return email.toLowerCase(Locale.ROOT).equals(config.accountEmail());
  }

  ObjectNode newObject() {
    return json.createObjectNode();
  }

  ArrayNode newArray() {
    return json.createArrayNode();
  }

  /** 선택 읽기면 본문 상한 초과와 403·404(요청 제한이 아닌 것)가 읽을 수 없는 본문이다. */
  private static CallFailure classify(Exception e, Availability availability) {
    // 두 예외는 고정 문구 메시지를 싣는다 — requireNonNull은 실패하지 않는다
    if (e instanceof GoogleContentTooLargeException tooLarge) {
      if (availability == Availability.OPTIONAL)
        throw new UnavailableContent(Objects.requireNonNull(tooLarge.getMessage()));
      throw tooLarge;
    }
    if (availability == Availability.ACL
        && e instanceof GoogleApiException api
        && api.status() == 403
        && api.aclUnreadable()) throw new UnreadableAcl();
    if (availability == Availability.OPTIONAL
        && e instanceof GoogleApiException api
        && UNAVAILABLE.contains(api.status())
        && !api.rateLimited())
      throw new UnavailableContent(Objects.requireNonNull(api.getMessage()));
    return GoogleErrors.classify(e);
  }

  /**
   * 원본 upsert: 외부 id `<종류>:<id>`, 버전은 `JSON.stringify(값)`의 sha256, 시각은 file의
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
            Optional.of(bodyContent().json("application/json", stringified)),
            RawAcl.notObserved());
    return new RawMutation.Upsert(item);
  }

  static RawMutation remove(String externalId, Instant observedAt) {
    return new RawMutation.Delete(
        externalId, Optional.empty(), Optional.empty(), Optional.empty(), observedAt);
  }

  /** 수집 제한 하나(unsupported_item). locator는 JSON. */
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
