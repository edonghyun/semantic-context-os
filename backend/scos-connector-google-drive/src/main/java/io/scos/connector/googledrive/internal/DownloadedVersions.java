package io.scos.connector.googledrive.internal;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 파일마다 원본을 내려받은 버전 — 같은 버전의 바이너리는 다시 받지 않는다. */
final class DownloadedVersions {
  private final ObjectNode versions;

  DownloadedVersions(ObjectNode versions) {
    this.versions = versions;
  }

  /** 체크포인트의 `downloadedVersions`: 객체이고 값이 모두 문자열이어야 한다. */
  static DownloadedVersions read(JsonNode stored) {
    ObjectNode versions = (ObjectNode) JsValues.object(stored).deepCopy();
    for (Map.Entry<String, JsonNode> e : versions.properties())
      if (!e.getValue().isString())
        throw new GoogleProtocolException("Invalid Google Drive download checkpoint");
    return new DownloadedVersions(versions);
  }

  boolean isDownloaded(String fileId, String version) {
    return JsValues.is(versions.get(fileId), version);
  }

  void record(String fileId, String version) {
    versions.put(fileId, version);
  }

  void forget(String fileId) {
    versions.remove(fileId);
  }

  ObjectNode json() {
    return versions.deepCopy();
  }
}
