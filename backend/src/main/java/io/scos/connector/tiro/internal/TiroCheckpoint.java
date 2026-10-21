package io.scos.connector.tiro.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.Checkpoint;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tiro 체크포인트(TS tiro-adapter.ts, F12 저장 호환): schemaVersion 1, `{scope, cursor}` — scope는 수집 범위 문자열,
 * cursor는 다음 목록 커서(마지막 페이지 뒤 null). 커서는 기록만 하고 재개에 쓰지 않는다: 한 실행이 목록 전체를 봐야 complete_snapshot 정리가 앞
 * 페이지의 노트를 지우지 않는다. 저장된 체크포인트는 범위가 같고 형식이 맞는지만 확인한다.
 */
public final class TiroCheckpoint {
  static final int SCHEMA_VERSION = 1;
  static final String INVALID = "Invalid Tiro checkpoint or changed collection scope";

  private TiroCheckpoint() {}

  /** 저장된 체크포인트가 이 범위의 Tiro 체크포인트인지 확인한다. 아니면 invalid_checkpoint. */
  public static void requireSameScope(
      ObjectMapper json, Optional<Checkpoint> stored, String scope) {
    if (stored.isEmpty()) return;
    JsonNode value = json.readTree(stored.get().valueJson());
    if (stored.get().schemaVersion() != SCHEMA_VERSION || !value.isObject()) throw invalid();
    JsonNode storedScope = value.get("scope");
    JsonNode cursor = value.get("cursor");
    if (storedScope == null || !storedScope.isString() || !storedScope.asString().equals(scope))
      throw invalid();
    if (cursor == null || !(cursor.isNull() || (cursor.isString() && !cursor.asString().isEmpty())))
      throw invalid();
  }

  public static Checkpoint write(String scope, Optional<String> cursor) {
    ObjectNode value = JsonNodeFactory.instance.objectNode().put("scope", scope);
    value.put("cursor", cursor.orElse(null));
    return new Checkpoint(SCHEMA_VERSION, JsJson.stringify(value));
  }

  /** 내부 분류 invalid_checkpoint, run errorKind는 TS와 같은 "Error"({@link TiroProtocolException}). */
  private static TiroProtocolException invalid() {
    return new TiroProtocolException(CallFailure.of(CallFailure.Kind.INVALID_CHECKPOINT, INVALID));
  }
}
