package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import java.util.List;

/**
 * 유닛 id(TS core/shared/hash.ts:7-19, G4·F-3): 첫 등장은 `sha256(sourceId|documentId|headingPath)`, 반복
 * 헤딩은 `sha256(JSON.stringify(["heading-occurrence", sourceId, documentId, headingPath, n]))` — 앞 16
 * hex.
 */
final class UnitIds {
  private UnitIds() {}

  static String make(String sourceId, String documentId, String headingPath, int occurrence) {
    String identity =
        occurrence == 1
            ? sourceId + "|" + documentId + "|" + headingPath
            : "["
                + String.join(
                    ",",
                    List.of(
                        jsonString("heading-occurrence"),
                        jsonString(sourceId),
                        jsonString(documentId),
                        jsonString(headingPath),
                        Integer.toString(occurrence)))
                + "]";
    return Text.sha256Hex(identity).substring(0, 16);
  }

  /** JSON.stringify 문자열 이스케이프: `"`·`\`·제어문자(짧은 형식 또는 \\u00xx)·홀로 남은 서로게이트(\\udxxx). 비ASCII는 그대로. */
  static String jsonString(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          boolean lone =
              Character.isHighSurrogate(c)
                  ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                  : Character.isLowSurrogate(c)
                      && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
          if (c < 0x20 || lone) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }
}
