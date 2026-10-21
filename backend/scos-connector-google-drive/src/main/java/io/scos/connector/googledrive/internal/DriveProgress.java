package io.scos.connector.googledrive.internal;

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
 * Drive run의 진행 상태(TS DriveProgress, 체크포인트 v1): 계정(permissionId), 작업 큐, 이번 탐색에서 본 파일, 내려받은 원본 버전,
 * 변경 토큰(`user`· `drive:<id>`), 수집한 파일의 드라이브 소속과 시트 범위 원본 id. 작업은 TS 객체처럼 JSON으로 다룬다 — 이어 읽기는 작업을
 * 복사해 pageToken만 바꾼다. 맵은 JS 객체 순서로 돈다({@link JsJson#entries}).
 */
final class DriveProgress {
  final String accountId;
  private List<DriveTask> pending;
  final Set<String> seen;
  final ObjectNode downloadedVersions;
  final ObjectNode changeTokens;
  final ObjectNode files;
  private final ObjectMapper json;

  private DriveProgress(
      ObjectMapper json,
      String accountId,
      List<DriveTask> pending,
      Set<String> seen,
      ObjectNode downloadedVersions,
      ObjectNode changeTokens,
      ObjectNode files) {
    this.json = json;
    this.accountId = accountId;
    this.pending = pending;
    this.seen = seen;
    this.downloadedVersions = downloadedVersions;
    this.changeTokens = changeTokens;
    this.files = files;
  }

  /** TS resume: 체크포인트가 없거나 큐가 비었으면 처음부터(변경 피드 → 내 파일 → 공유 드라이브). 다른 계정·형식 위반은 요청 없이 실패한다. */
  static DriveProgress resume(ObjectMapper json, Optional<Checkpoint> stored, String accountId) {
    if (stored.isEmpty())
      return new DriveProgress(
          json,
          accountId,
          new ArrayList<>(DriveTask.roots()),
          new LinkedHashSet<>(),
          json.createObjectNode(),
          json.createObjectNode(),
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
    ObjectNode versions = (ObjectNode) JsValues.object(value.get("downloadedVersions")).deepCopy();
    for (Map.Entry<String, JsonNode> e : versions.properties())
      if (!e.getValue().isString()) throw invalid("Invalid Google Drive download checkpoint");
    ObjectNode tokens = objectOrEmpty(json, value.get("changeTokens"));
    for (Map.Entry<String, JsonNode> e : tokens.properties())
      if (!JsValues.nonEmptyString(e.getValue()))
        throw invalid("Invalid Google Drive change checkpoint");
    ObjectNode files = objectOrEmpty(json, value.get("files"));
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

  /** TS `value ?? {}`(null·undefined는 빈 객체) 뒤 googleObject. */
  private static ObjectNode objectOrEmpty(ObjectMapper json, JsonNode value) {
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
    return pending.getFirst();
  }

  /** 첫 작업을 끝냈다: 그 자리에 다음 작업들을 넣는다(TS `[...next, ...pending.slice(1)]`). */
  void advance(List<DriveTask> next) {
    List<DriveTask> queue = new ArrayList<>(next);
    queue.addAll(pending.subList(1, pending.size()));
    pending = queue;
  }

  /** 첫 작업(지금 하는 것)은 두고 조건에 맞는 작업을 버린다. */
  void dropQueued(Predicate<DriveTask> drop) {
    List<DriveTask> kept = new ArrayList<>();
    for (int i = 0; i < pending.size(); i++)
      if (i == 0 || !drop.test(pending.get(i))) kept.add(pending.get(i));
    pending = kept;
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

  /** TS `progress.files[id] = {driveId?, children}`(이미 있으면 그 자리에서 바꾼다). */
  void recordFile(String fileId, Optional<String> driveId, List<String> children) {
    ObjectNode entry = json.createObjectNode();
    driveId.ifPresent(d -> entry.put("driveId", d));
    ArrayNode array = entry.putArray("children");
    children.forEach(array::add);
    files.set(fileId, entry);
  }

  void forget(String fileId) {
    files.remove(fileId);
    downloadedVersions.remove(fileId);
  }

  /** 지금 상태의 체크포인트 v1(TS structuredClone(progress)). */
  Checkpoint checkpoint() {
    ObjectNode value = json.createObjectNode().put("accountId", accountId);
    ArrayNode queue = value.putArray("pending");
    pending.forEach(task -> queue.add(task.json()));
    ArrayNode seenFiles = value.putArray("seenFiles");
    seen.forEach(seenFiles::add);
    value.set("downloadedVersions", downloadedVersions.deepCopy());
    value.set("changeTokens", changeTokens.deepCopy());
    value.set("files", files.deepCopy());
    return new Checkpoint(1, JsJson.stringify(value));
  }
}
