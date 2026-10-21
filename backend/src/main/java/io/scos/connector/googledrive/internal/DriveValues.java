package io.scos.connector.googledrive.internal;

import io.scos.engine.support.text.JsJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Drive 응답 객체 도우미(투영 루트와 렌더러가 함께 쓴다). */
public final class DriveValues {
  private DriveValues() {}

  /** 객체 값을 JS 객체 순서로(Object.values). */
  public static List<JsonNode> values(JsonNode object) {
    List<JsonNode> out = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : JsJson.entries(object)) out.add(e.getValue());
    return out;
  }
}
