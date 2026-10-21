package io.scos.connector.tiro.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tiro 소스 설정(TS tiro-adapter.ts configuration): schemaVersion 1, `workspaceGuid`와 `folderId` 또는
 * `allNotes: true` 중 정확히 하나. 식별자는 `[A-Za-z0-9_-]+`, 모르는 키는 거부. 수집 범위({@link Selection})는 체크포인트의
 * scope 문자열이 된다 — TS `JSON.stringify(configuration)`과 같은 키 순서(workspaceGuid 다음 folderId 또는
 * allNotes).
 */
public record TiroConfiguration(String workspaceGuid, TiroConfiguration.Selection selection) {
  /** 수집 선택: 폴더 하나(하위 폴더 포함) 또는 키로 읽을 수 있는 모든 노트. */
  public sealed interface Selection {
    record Folder(String folderId) implements Selection {}

    record AllNotes() implements Selection {}
  }

  static final String INVALID =
      "Tiro requires workspaceGuid and exactly one of folderId or allNotes: true";
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Set<String> KEYS = Set.of("workspaceGuid", "folderId", "allNotes");

  public static TiroConfiguration read(ObjectMapper json, SourceConfig config) {
    JsonNode value = json.readTree(config.valueJson());
    if (config.schemaVersion() != 1 || !value.isObject()) throw invalid();
    for (String key : value.propertyNames()) if (!KEYS.contains(key)) throw invalid();
    if (!isId(value.get("workspaceGuid"))) throw invalid();
    boolean hasFolder = value.has("folderId");
    boolean hasAllNotes = value.has("allNotes");
    if (hasFolder == hasAllNotes) throw invalid();
    if (hasFolder && !isId(value.get("folderId"))) throw invalid();
    if (hasAllNotes && !(value.get("allNotes").isBoolean() && value.get("allNotes").asBoolean()))
      throw invalid();
    String workspace = value.get("workspaceGuid").asString();
    return new TiroConfiguration(
        workspace,
        hasFolder
            ? new Selection.Folder(value.get("folderId").asString())
            : new Selection.AllNotes());
  }

  /** 체크포인트에 남기는 수집 범위(TS `JSON.stringify(config)`). 범위가 바뀌면 저장된 체크포인트를 쓰지 않는다. */
  public String scope() {
    ObjectNode value = JsonNodeFactory.instance.objectNode().put("workspaceGuid", workspaceGuid);
    switch (selection) {
      case Selection.Folder f -> value.put("folderId", f.folderId());
      case Selection.AllNotes a -> value.put("allNotes", true);
    }
    return JsJson.stringify(value);
  }

  private static boolean isId(JsonNode node) {
    return node != null && node.isString() && ID.matcher(node.asString()).matches();
  }

  private static CallFailureException invalid() {
    return new CallFailureException(
        "tiro.configuration", CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, INVALID));
  }
}
