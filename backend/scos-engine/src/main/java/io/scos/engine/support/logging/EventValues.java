package io.scos.engine.support.logging;

import io.scos.engine.support.PureFunctions;
import io.scos.engine.support.text.JsJson;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 이벤트 값의 순서와 JSON 표현을 사람·기계 로그에서 함께 사용한다. */
@PureFunctions
public final class EventValues {
  private EventValues() {}

  public static Map<String, Object> detail(Object... keyValues) {
    if (keyValues.length % 2 != 0) throw new IllegalArgumentException("key-value pairs expected");
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) out.put((String) keyValues[i], keyValues[i + 1]);
    return out;
  }

  public static String json(@Nullable Object value) {
    if (value == null) return "null";
    if (value instanceof JsonNode node) return JsJson.stringify(node);
    if (value instanceof String s) return JsJson.string(s);
    if (value instanceof Boolean b) return b.toString();
    if (value instanceof Number n) return JsJson.number(n.doubleValue());
    if (value instanceof Map<?, ?> map) {
      StringBuilder out = new StringBuilder("{");
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (out.length() > 1) out.append(',');
        out.append(JsJson.string(String.valueOf(e.getKey())))
            .append(':')
            .append(json(e.getValue()));
      }
      return out.append('}').toString();
    }
    if (value instanceof Collection<?> list) {
      StringBuilder out = new StringBuilder("[");
      for (Object element : list) {
        if (out.length() > 1) out.append(',');
        out.append(json(element));
      }
      return out.append(']').toString();
    }
    return JsJson.string(String.valueOf(value));
  }
}
