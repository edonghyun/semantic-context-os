package io.scos.connector.googledrive.internal;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drive 작업 큐의 작업 하나(TS DriveTask 판별 공용체). 종류마다 record이고 수집기는 종류를 {@code switch}로 고른다(모르는 종류가 목록
 * 요청으로 흐르지 않는다). 각 작업은 체크포인트·거부 locator에 그대로 쓰는 TS 모양 JSON({@link #json()})을 가진다 — 새 작업은 TS 리터럴과 같은
 * 키 순서로 만들고, 체크포인트에서 읽은 작업은 원래 JSON을 보존한다(TS `{...task, pageToken}`처럼 이어 읽기는 그 JSON을 복사해 pageToken만
 * 바꾼다).
 */
sealed interface DriveTask {
  /** TS 모양 JSON(바꾸지 않는다 — 큐는 작업을 공유한다). */
  ObjectNode json();

  /** 변경 피드 한 페이지(driveId가 없으면 사용자 피드). */
  record Changes(Optional<String> driveId, Optional<String> pageToken, ObjectNode json)
      implements DriveTask {
    Changes withPageToken(String token) {
      return new Changes(driveId, Optional.of(token), json.deepCopy().put("pageToken", token));
    }
  }

  /** 파일 목록 한 페이지(driveId가 없으면 내 파일). */
  record Files(Optional<String> driveId, Optional<String> pageToken, ObjectNode json)
      implements DriveTask {
    Files withPageToken(String token) {
      return new Files(driveId, Optional.of(token), json.deepCopy().put("pageToken", token));
    }
  }

  /** 공유 드라이브 목록 한 페이지. */
  record Drives(Optional<String> pageToken, ObjectNode json) implements DriveTask {
    Drives withPageToken(String token) {
      return new Drives(Optional.of(token), json.deepCopy().put("pageToken", token));
    }
  }

  /** 파일 하나 수집. */
  record File(String id, ObjectNode json) implements DriveTask {}

  /** 시트 범위 하나 수집. file은 범위 원본의 시각·주소에 쓰는 파일 메타데이터다. */
  record Sheet(String id, String key, String range, String version, JsonNode file, ObjectNode json)
      implements DriveTask {}

  /** 파일(또는 drive면 공유 드라이브와 그 파일들) 삭제. */
  record Remove(String id, boolean drive, ObjectNode json) implements DriveTask {}

  // ---- 새 작업(TS 리터럴과 같은 키 순서) ----

  static List<DriveTask> roots() {
    return List.of(
        new Changes(Optional.empty(), Optional.empty(), node("changes")),
        new Files(Optional.empty(), Optional.empty(), node("files")),
        new Drives(Optional.empty(), node("drives")));
  }

  static Changes changesOf(String driveId) {
    return new Changes(
        Optional.of(driveId), Optional.empty(), node("changes").put("driveId", driveId));
  }

  static Files filesOf(String driveId) {
    return new Files(Optional.of(driveId), Optional.empty(), node("files").put("driveId", driveId));
  }

  static File file(String id) {
    return new File(id, node("file").put("id", id));
  }

  static Remove remove(String id) {
    return new Remove(id, false, node("remove").put("id", id));
  }

  static Remove removeDrive(String id) {
    return new Remove(id, true, node("remove").put("id", id).put("drive", true));
  }

  static Sheet sheet(String id, SheetRanges.SheetRange range, String version, JsonNode file) {
    ObjectNode json =
        node("sheet")
            .put("id", id)
            .put("key", range.key())
            .put("range", range.range())
            .put("version", version);
    json.set("file", file.deepCopy());
    return new Sheet(id, range.key(), range.range(), version, file.deepCopy(), json);
  }

  private static ObjectNode node(String kind) {
    return JsonNodeFactory.instance.objectNode().put("kind", kind);
  }

  // ---- 체크포인트에서 읽기(TS resume의 작업 검증) ----

  /** 체크포인트의 작업 하나. 위반은 TS 메시지("Invalid Google Drive checkpoint task" 등)로 실패한다. */
  static DriveTask parse(JsonNode entry) {
    ObjectNode task = (ObjectNode) JsValues.object(entry).deepCopy();
    JsonNode kind = task.get("kind");
    String kindText = JsValues.string(kind);
    boolean identified = List.of("file", "sheet", "remove").contains(kindText);
    boolean invalid =
        !List.of("files", "drives", "file", "sheet", "changes", "remove").contains(kindText)
            || (identified && !JsValues.nonEmptyString(task.get("id")))
            || (JsValues.is(kind, "remove")
                && task.get("drive") != null
                && !task.get("drive").isBoolean())
            || (JsValues.is(kind, "sheet") && invalidSheet(task))
            || List.of("driveId", "pageToken").stream()
                .map(task::get)
                .anyMatch(v -> v != null && !JsValues.nonEmptyString(v));
    if (invalid) throw new GoogleProtocolException("Invalid Google Drive checkpoint task");
    Optional<String> driveId = text(task, "driveId");
    Optional<String> pageToken = text(task, "pageToken");
    return switch (kindText) {
      case "changes" -> new Changes(driveId, pageToken, task);
      case "files" -> new Files(driveId, pageToken, task);
      case "drives" -> new Drives(pageToken, task);
      case "file" -> new File(task.get("id").asString(), task);
      case "sheet" ->
          new Sheet(
              task.get("id").asString(),
              task.get("key").asString(),
              task.get("range").asString(),
              task.get("version").asString(),
              task.get("file"),
              task);
      case "remove" ->
          new Remove(task.get("id").asString(), JsValues.truthy(task.get("drive")), task);
      default -> throw new IllegalStateException("검증한 작업 종류: " + kindText);
    };
  }

  /** 범위 키·A1 범위·버전이 비어 있지 않은 문자열이고 file에 id가 있다(file이 객체가 아니면 "Invalid Google API object"). */
  private static boolean invalidSheet(ObjectNode task) {
    if (List.of("key", "range", "version").stream()
        .map(task::get)
        .anyMatch(v -> !JsValues.nonEmptyString(v))) return true;
    return !JsValues.truthy(JsValues.object(task.get("file")).get("id"));
  }

  private static Optional<String> text(ObjectNode task, String field) {
    JsonNode v = task.get(field);
    return v == null ? Optional.empty() : Optional.of(v.asString());
  }
}
