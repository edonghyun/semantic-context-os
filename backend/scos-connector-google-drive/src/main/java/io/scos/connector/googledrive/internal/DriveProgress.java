package io.scos.connector.googledrive.internal;

import io.scos.engine.support.call.WorkQueue;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.Checkpoint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drive run의 진행 상태(체크포인트 v1): 계정(permissionId), 작업 큐, 이번 탐색에서 본 파일, 내려받은 원본 버전({@link
 * DownloadedVersions}), 변경 토큰({@link ChangeTokens}), 수집한 파일의 드라이브 소속과 시트 범위 원본 id. 맵은 JS 객체 순서로
 * 돈다({@link JsJson#entries}) — 체크포인트 바이트가 저장 형식과 같아야 한다.
 */
final class DriveProgress {
  private final String accountId;
  private WorkQueue<DriveTask> pending;
  private final Set<String> seen;
  private final DownloadedVersions downloadedVersions;
  private final ChangeTokens changeTokens;
  private final ObjectNode files;
  private final ObjectMapper json;

  private DriveProgress(
      ObjectMapper json,
      String accountId,
      List<DriveTask> pending,
      Set<String> seen,
      DownloadedVersions downloadedVersions,
      ChangeTokens changeTokens,
      ObjectNode files) {
    this.json = json;
    this.accountId = accountId;
    this.pending = WorkQueue.of(pending);
    this.seen = seen;
    this.downloadedVersions = downloadedVersions;
    this.changeTokens = changeTokens;
    this.files = files;
  }

  ChangeTokens changeTokens() {
    return changeTokens;
  }

  DownloadedVersions downloadedVersions() {
    return downloadedVersions;
  }

  /** 이번 탐색에서 처음 본 파일이면 true(그리고 본 것으로 남긴다). */
  boolean firstSeen(String fileId) {
    return seen.add(fileId);
  }

  /** 체크포인트가 없거나 큐가 비었으면 처음부터(변경 피드 → 내 파일 → 공유 드라이브). 다른 계정·형식 위반은 요청 없이 실패한다. */
  static DriveProgress resume(ObjectMapper json, Optional<Checkpoint> stored, String accountId) {
    if (stored.isEmpty())
      return new DriveProgress(
          json,
          accountId,
          new ArrayList<>(DriveTask.roots()),
          new LinkedHashSet<>(),
          new DownloadedVersions(json.createObjectNode()),
          new ChangeTokens(json.createObjectNode()),
          json.createObjectNode());
    JsonNode value = JsValues.object(json.readTree(stored.get().valueJson()));
    JsonNode pendingNode = value.get("pending");
    JsonNode seenNode = value.get("seenFiles");
    if (stored.get().schemaVersion() != 1
        || !JsValues.is(value.get("accountId"), accountId)
        || pendingNode == null
        || !pendingNode.isArray()
        || seenNode == null
        || !seenNode.isArray()
        || anyNonString(seenNode)) throw invalid("Invalid Google Drive checkpoint");
    List<DriveTask> pending = new ArrayList<>();
    for (JsonNode entry : pendingNode) pending.add(DriveTask.parse(entry));
    DownloadedVersions versions = DownloadedVersions.read(value.get("downloadedVersions"));
    ChangeTokens tokens = ChangeTokens.read(json, value.get("changeTokens"));
    ObjectNode files = readFiles(json, value.get("files"));
    Set<String> seen = new LinkedHashSet<>();
    if (!pending.isEmpty()) seenNode.forEach(id -> seen.add(id.asString()));
    return new DriveProgress(
        json,
        accountId,
        pending.isEmpty() ? new ArrayList<>(DriveTask.roots()) : pending,
        seen,
        versions,
        tokens,
        files);
  }

  /** 체크포인트의 `files`(JS `?? {}`): 파일마다 `{driveId?: 문자열, children: 문자열 배열}`. */
  private static ObjectNode readFiles(ObjectMapper json, JsonNode stored) {
    ObjectNode files = objectOrEmpty(json, stored);
    for (Map.Entry<String, JsonNode> e : files.properties()) {
      JsonNode file = JsValues.object(e.getValue());
      JsonNode children = file.get("children");
      JsonNode driveId = file.get("driveId");
      if (children == null
          || !children.isArray()
          || anyNonString(children)
          || (driveId != null && !driveId.isString()))
        throw invalid("Invalid Google Drive file checkpoint");
    }
    return files;
  }

  /** JS `value ?? {}`(null·undefined는 빈 객체) 뒤 객체 검사. */
  static ObjectNode objectOrEmpty(ObjectMapper json, JsonNode value) {
    if (value == null || value.isNull()) return json.createObjectNode();
    return (ObjectNode) JsValues.object(value).deepCopy();
  }

  private static boolean anyNonString(JsonNode array) {
    for (JsonNode v : array) if (!v.isString()) return true;
    return false;
  }

  private static GoogleProtocolException invalid(String message) {
    return new GoogleProtocolException(message);
  }

  boolean hasPending() {
    return !pending.isEmpty();
  }

  /** 큐의 첫 작업(지금 할 것). */
  DriveTask head() {
    return pending.head();
  }

  /** 첫 작업을 끝냈다: 그 자리에 다음 작업들을 넣는다. */
  void advance(List<DriveTask> next) {
    pending = pending.afterHead(1, next, List.of());
  }

  /** 첫 작업(지금 하는 것)은 두고 조건에 맞는 작업을 버린다. */
  void dropQueued(Predicate<DriveTask> drop) {
    pending = pending.withoutQueued(drop);
  }

  /** 이 파일의 시트 범위 원본 id(체크포인트에 남은 것). */
  List<String> children(String fileId) {
    JsonNode file = files.get(fileId);
    List<String> out = new ArrayList<>();
    if (file != null) file.get("children").forEach(c -> out.add(c.asString()));
    return out;
  }

  /** 이 공유 드라이브 소속으로 기록된 파일 id(JS 객체 순서). */
  List<String> filesInDrive(String driveId) {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : JsJson.entries(files))
      if (JsValues.is(e.getValue().get("driveId"), driveId)) out.add(e.getKey());
    return out;
  }

  /** 파일 기록 `{driveId?, children}`을 둔다(이미 있으면 그 자리에서 바꾼다). */
  void recordFile(String fileId, Optional<String> driveId, List<String> children) {
    ObjectNode entry = json.createObjectNode();
    driveId.ifPresent(d -> entry.put("driveId", d));
    ArrayNode array = entry.putArray("children");
    children.forEach(array::add);
    files.set(fileId, entry);
  }

  void forget(String fileId) {
    files.remove(fileId);
    downloadedVersions.forget(fileId);
  }

  /** 지금 상태의 체크포인트 v1. */
  Checkpoint checkpoint() {
    ObjectNode value = json.createObjectNode().put("accountId", accountId);
    ArrayNode queue = value.putArray("pending");
    pending.items().forEach(task -> queue.add(task.json()));
    ArrayNode seenFiles = value.putArray("seenFiles");
    seen.forEach(seenFiles::add);
    value.set("downloadedVersions", downloadedVersions.json());
    value.set("changeTokens", changeTokens.json());
    value.set("files", files.deepCopy());
    return new Checkpoint(1, JsJson.stringify(value));
  }
}
