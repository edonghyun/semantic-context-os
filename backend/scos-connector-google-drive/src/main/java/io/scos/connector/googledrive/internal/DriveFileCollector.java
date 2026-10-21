package io.scos.connector.googledrive.internal;

import io.scos.connector.googledrive.internal.DriveCalls.Availability;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawContentTooLargeException;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 파일 하나의 수집(TS collectFile·download·readAll). 메타데이터를 읽고(403·404면 파일 거부), 휴지통이면 지운다. 형식별 본문: Docs(모든
 * 탭)·Sheets(메타데이터 + 범위 작업)·Slides는 API JSON, 추출기 없는 Workspace 형식은 제한, 그 밖은 원본 다운로드(설정이 켜졌을 때 —
 * text/·application/json은 본문도 디코드하고 늘 다시 받는다, 그 밖은 같은 버전이면 다시 받지 않는다). 폴더·바로가기에는 본문·댓글이 없다. 댓글과 답글은
 * 끝까지 읽는다. 읽을 수 없는 본문·댓글은 limitations와 거부로 남는다.
 *
 * <p>파일 원본 JSON(envelope)의 키 순서는 TS 객체에 넣는 순서와
 * 같다(file·extraction·limitations·workspace·sheetRangeKeys·text· originalStored·comments) — 버전 해시가
 * TS와 같아야 한다. envelope가 캡처 상한을 넘으면 큰 선택 구역(workspace·text·comments)부터 뺀다.
 */
final class DriveFileCollector {
  static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
  static final String FILE_FIELDS =
      "id,name,mimeType,description,parents,driveId,webViewLink,createdTime,modifiedTime,version,size,md5Checksum,owners(displayName,emailAddress),shared,trashed,capabilities(canDownload),shortcutDetails";
  static final String WORKSPACE = "application/vnd.google-apps.";
  private static final List<String> OPTIONAL_SECTIONS = List.of("workspace", "text", "comments");
  private static final int BAD_GATEWAY = 502;

  /** 파일 하나의 결과: 원본 변경, 수집 제한, 큐 앞에 넣을 범위 작업, 휴지통이 아니면 메타데이터. */
  record Collected(
      List<RawMutation> mutations,
      List<RejectedItem> rejections,
      List<DriveTask.Sheet> next,
      Optional<ObjectNode> metadata) {}

  private final DriveCalls calls;

  DriveFileCollector(DriveCalls calls) {
    this.calls = calls;
  }

  Collected collect(String id, Instant observedAt, ObjectNode downloaded) {
    String encoded = GoogleQuery.component(id);
    ObjectNode file =
        calls.json(
            GoogleService.DRIVE,
            "/drive/v3/files/"
                + encoded
                + "?supportsAllDrives=true&fields="
                + GoogleQuery.component(FILE_FIELDS),
            Availability.OPTIONAL);
    JsonNode mimeType = file.get("mimeType");
    if (!JsValues.is(file.get("id"), id) || mimeType == null || !mimeType.isString())
      throw new GoogleProtocolException("Google Drive returned wrong file identity or MIME type");
    if (JsValues.isTrue(file.get("trashed")))
      return new Collected(
          List.of(
              DriveCalls.remove(RawKind.FILE.externalId(id), observedAt),
              DriveCalls.remove(RawKind.BINARY.externalId(id), observedAt)),
          List.of(),
          List.of(),
          Optional.empty());
    Envelope envelope = new Envelope(id, file, observedAt);
    String mime = mimeType.asString();
    if (!mime.equals(WORKSPACE + "folder") && !mime.equals(WORKSPACE + "shortcut")) {
      try {
        content(envelope, encoded, mime, downloaded);
      } catch (UnavailableContent | RawContentTooLargeException e) {
        envelope.gap("content", e.getMessage());
      }
      try {
        envelope.content.set("comments", comments(encoded));
      } catch (UnavailableContent e) {
        envelope.gap("comments", e.getMessage());
      }
    }
    envelope.store();
    return new Collected(envelope.mutations, envelope.rejections, envelope.next, Optional.of(file));
  }

  private void content(Envelope envelope, String encoded, String mime, ObjectNode downloaded) {
    ObjectNode file = envelope.file;
    ObjectNode content = envelope.content;
    JsonNode capabilities = file.get("capabilities");
    if (JsValues.truthy(capabilities)
        && JsValues.isFalse(JsValues.object(capabilities).get("canDownload")))
      throw new UnavailableContent("File content copying/downloading is restricted");
    if (mime.equals(WORKSPACE + "document")) {
      content.set(
          "workspace",
          calls.json(
              GoogleService.DOCS,
              "/v1/documents/" + encoded + "?includeTabsContent=true",
              Availability.OPTIONAL));
      content.put("extraction", "google_docs");
    } else if (mime.equals(WORKSPACE + "spreadsheet")) {
      spreadsheet(envelope, encoded);
    } else if (mime.equals(WORKSPACE + "presentation")) {
      content.set(
          "workspace",
          calls.json(GoogleService.SLIDES, "/v1/presentations/" + encoded, Availability.OPTIONAL));
      content.put("extraction", "google_slides");
    } else if (mime.startsWith(WORKSPACE)) {
      envelope.gap("content", "Workspace format has no content extractor");
    } else if (calls.config().downloadBinaries()) {
      original(envelope, mime, downloaded);
    } else envelope.gap("content", "Binary downloads disabled by configuration");
  }

  /** 시트: 메타데이터(시트 속성)를 envelope에 두고 GRID 범위마다 작업을 만든다. 범위 원본 id는 sheetRangeKeys로 남긴다. */
  private void spreadsheet(Envelope envelope, String encoded) {
    ObjectNode workspace =
        calls.json(
            GoogleService.SHEETS,
            "/v4/spreadsheets/"
                + encoded
                + "?fields="
                + GoogleQuery.component("spreadsheetId,properties(title),sheets(properties)"),
            Availability.OPTIONAL);
    envelope.content.set("workspace", workspace);
    JsonNode version = envelope.file.get("version");
    if (version == null || !version.isString())
      throw new GoogleProtocolException("Spreadsheet has no version");
    List<SheetRanges.SheetRange> ranges = SheetRanges.of(workspace);
    for (JsonNode sheet : workspace.get("sheets")) {
      JsonNode type = JsValues.object(JsValues.object(sheet).get("properties")).get("sheetType");
      if (type != null && !JsValues.is(type, "GRID")) {
        envelope.gap("sheets", "Non-grid sheets retain metadata only");
        break;
      }
    }
    ArrayNode keys = envelope.content.putArray("sheetRangeKeys");
    for (SheetRanges.SheetRange range : ranges) {
      keys.add(RawKind.SHEET.externalId(envelope.id + ":" + range.key()));
      envelope.next.add(DriveTask.sheet(envelope.id, range, version.asString(), envelope.file));
    }
    envelope.content.put("extraction", "google_sheets");
  }

  /** 원본 다운로드: 텍스트는 본문도, 바이너리는 같은 버전이면 다시 받지 않는다. 원본은 blob(gdrive.binary)이다. */
  private void original(Envelope envelope, String mime, ObjectNode downloaded) {
    ObjectNode file = envelope.file;
    ObjectNode content = envelope.content;
    boolean isText = mime.startsWith("text/") || mime.equals("application/json");
    JsonNode version = file.get("version");
    boolean sameVersion =
        version != null
            && version.isString()
            && JsValues.is(downloaded.get(envelope.id), version.asString());
    if (sameVersion && !isText) content.put("originalStored", true);
    else {
      byte[] bytes = download(file);
      if (isText) {
        content.put("text", GoogleDriveSession.text(bytes));
        content.put("extraction", "text");
      }
      RawContent captured = calls.input().content().bytes(mime, bytes);
      envelope.mutations.add(binary(envelope, isText, captured));
      content.put("originalStored", true);
      if (version != null && version.isString()) downloaded.put(envelope.id, version.asString());
    }
    if (!isText) envelope.limitations().add("Binary original stored; body/OCR not extracted");
  }

  private RawMutation binary(Envelope envelope, boolean isText, RawContent captured) {
    ObjectNode file = envelope.file;
    JsonNode version = file.get("version");
    JsonNode modified = file.get("modifiedTime");
    String sourceVersion =
        present(version)
            ? JsValues.string(version)
            : present(modified)
                ? JsValues.string(modified)
                : DriveCalls.ISO_MILLIS.format(envelope.observedAt);
    ObjectNode metadata =
        calls
            .mapper()
            .createObjectNode()
            .put("fileId", envelope.id)
            .put("extraction", isText ? "text" : "not_extracted");
    return new RawMutation.Upsert(
        new RawItem(
            RawKind.BINARY.externalId(envelope.id),
            RawKind.BINARY.resourceType(),
            sourceVersion,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            envelope.observedAt,
            JsJson.stringify(metadata),
            Optional.of(captured)));
  }

  /** TS `??`: null·undefined가 아니다. */
  private static boolean present(JsonNode value) {
    return value != null && !value.isNull() && !value.isMissingNode();
  }

  /**
   * 원본 바이트(TS download): 크기를 알고 50 MiB를 넘으면 받지 않는다. 받은 크기가 메타데이터와 다르면 502 incomplete_download(재시도).
   */
  private byte[] download(ObjectNode file) {
    JsonNode size = file.get("size");
    double expected = JsValues.number(size);
    if (size != null && (!Double.isFinite(expected) || expected > MAX_FILE_BYTES))
      throw new UnavailableContent("Original exceeds the 50 MiB capture limit");
    String id = JsValues.string(file.get("id"));
    return calls
        .input()
        .calls()
        .execute(
            "google-drive.download",
            timeout -> {
              byte[] bytes = calls.session().download(id, timeout, MAX_FILE_BYTES);
              if (size != null && bytes.length != expected)
                throw GoogleApiException.of(
                    BAD_GATEWAY, GoogleApiException.Reason.INCOMPLETE_DOWNLOAD);
              return bytes;
            },
            e -> {
              if (e instanceof GoogleContentTooLargeException tooLarge)
                throw new UnavailableContent(tooLarge.getMessage());
              DriveCalls.unavailableUnlessRateLimited(e);
              return GoogleErrors.classify(e);
            },
            calls.input().cancel());
  }

  /** 댓글 전체와 각 댓글의 답글 전체(지운 댓글은 답글을 읽지 않는다). */
  private ArrayNode comments(String encoded) {
    List<ObjectNode> comments = readAll("/drive/v3/files/" + encoded + "/comments", "comments");
    for (ObjectNode comment : comments) {
      if (JsValues.isTrue(comment.get("deleted"))) continue;
      JsonNode commentId = comment.get("id");
      if (commentId == null || !commentId.isString())
        throw new GoogleProtocolException("Google Drive comment has no identity");
      ArrayNode replies = calls.mapper().createArrayNode();
      readAll(
              "/drive/v3/files/"
                  + encoded
                  + "/comments/"
                  + GoogleQuery.component(commentId.asString())
                  + "/replies",
              "replies")
          .forEach(replies::add);
      comment.set("replies", replies);
    }
    ArrayNode array = calls.mapper().createArrayNode();
    comments.forEach(array::add);
    return array;
  }

  /** 자식 목록 전체(TS readAll): 페이지 토큰이 비거나 앞에서 본 토큰이면 실패한다. */
  private List<ObjectNode> readAll(String path, String key) {
    List<ObjectNode> entries = new ArrayList<>();
    Set<String> tokens = new HashSet<>();
    Optional<String> pageToken = Optional.empty();
    while (true) {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("fields", "nextPageToken," + key + "(*)");
      query.put("pageSize", "100");
      query.put("includeDeleted", "false");
      pageToken.ifPresent(t -> query.put("pageToken", t));
      ObjectNode page =
          calls.json(
              GoogleService.DRIVE, path + "?" + GoogleQuery.form(query), Availability.OPTIONAL);
      JsonNode items = page.get(key);
      if (items == null || !items.isArray())
        throw new GoogleProtocolException("Invalid Google Drive child collection");
      for (JsonNode item : items) entries.add((ObjectNode) JsValues.object(item));
      JsonNode next = page.get("nextPageToken");
      if (next == null) return entries;
      if (!JsValues.nonEmptyString(next) || tokens.contains(next.asString()))
        throw new GoogleProtocolException("Google Drive child page token did not advance");
      pageToken = Optional.of(next.asString());
      tokens.add(next.asString());
    }
  }

  /** 파일 원본 JSON을 만드는 중의 상태와, 상한에 맞춰 저장하기. */
  private final class Envelope {
    final String id;
    final ObjectNode file;
    final Instant observedAt;
    final ObjectNode content;
    final List<RawMutation> mutations = new ArrayList<>();
    final List<RejectedItem> rejections = new ArrayList<>();
    final List<DriveTask.Sheet> next = new ArrayList<>();

    Envelope(String id, ObjectNode file, Instant observedAt) {
      this.id = id;
      this.file = file;
      this.observedAt = observedAt;
      content = calls.mapper().createObjectNode();
      content.set("file", file);
      content.put("extraction", "metadata_only");
      content.putArray("limitations");
    }

    ArrayNode limitations() {
      return (ArrayNode) content.get("limitations");
    }

    /** 수집 제한 하나: limitations에 `<구역>: <사유>`, 거부에 {fileId, section}. */
    void gap(String section, String message) {
      limitations().add(section + ": " + message);
      rejections.add(
          DriveCalls.rejection(
              calls.mapper().createObjectNode().put("fileId", id).put("section", section),
              message,
              observedAt));
    }

    /** envelope를 저장한다. 상한을 넘으면 가장 큰 선택 구역부터 빼고 다시(메타데이터만 남아도 넘으면 파일 거부). */
    void store() {
      while (true) {
        try {
          mutations.add(calls.upsert(RawKind.FILE, id, content, observedAt, file));
          return;
        } catch (RawContentTooLargeException e) {
          Optional<String> section =
              OPTIONAL_SECTIONS.stream()
                  .filter(content::has)
                  .sorted(Comparator.comparingLong(this::size).reversed())
                  .findFirst();
          if (section.isEmpty())
            throw new UnavailableContent("File metadata exceeds the raw capture limit");
          content.remove(section.get());
          if (!section.get().equals("comments")) content.put("extraction", "metadata_only");
          gap(section.get(), e.getMessage());
        }
      }
    }

    private long size(String section) {
      return JsJson.stringify(content.get(section)).getBytes(StandardCharsets.UTF_8).length;
    }
  }
}
