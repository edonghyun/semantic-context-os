package io.scos.connector.googledrive.internal;

import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 변경 피드의 저장된 시작 토큰: 내 드라이브는 `user`, 공유 드라이브는 `drive:<id>` 키. 값이 JS truthy가 아니면 없는 것으로 본다. 체크포인트에는
 * 같은 키 순서로 실린다.
 */
final class ChangeTokens {
  private final ObjectNode tokens;

  ChangeTokens(ObjectNode tokens) {
    this.tokens = tokens;
  }

  /** 체크포인트의 `changeTokens`(JS `?? {}`): 값이 모두 비어 있지 않은 문자열이어야 한다. */
  static ChangeTokens read(ObjectMapper json, JsonNode stored) {
    ObjectNode tokens = DriveProgress.objectOrEmpty(json, stored);
    for (Map.Entry<String, JsonNode> e : tokens.properties())
      if (!JsValues.nonEmptyString(e.getValue()))
        throw new GoogleProtocolException("Invalid Google Drive change checkpoint");
    return new ChangeTokens(tokens);
  }

  /** 이 피드(드라이브 id 없으면 내 드라이브)의 저장된 토큰. */
  Optional<String> saved(Optional<String> driveId) {
    JsonNode saved = tokens.get(key(driveId));
    return JsValues.truthy(saved) ? Optional.of(saved.asString()) : Optional.empty();
  }

  void record(Optional<String> driveId, String token) {
    tokens.put(key(driveId), token);
  }

  /** 회수된 공유 드라이브의 토큰을 지운다. */
  void forgetDrive(String driveId) {
    tokens.remove(key(Optional.of(driveId)));
  }

  ObjectNode json() {
    return tokens.deepCopy();
  }

  private static String key(Optional<String> driveId) {
    return driveId.map(d -> "drive:" + d).orElse("user");
  }
}
