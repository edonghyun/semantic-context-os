package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import java.util.List;

/**
 * 유닛 id(G4·F-3): 첫 등장은 `sha256(sourceId|documentId|headingPath)`, 반복 헤딩은
 * `sha256(JSON.stringify(["heading-occurrence", sourceId, documentId, headingPath, n]))` — 앞 16
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
                        JsJson.string("heading-occurrence"),
                        JsJson.string(sourceId),
                        JsJson.string(documentId),
                        JsJson.string(headingPath),
                        Integer.toString(occurrence)))
                + "]";
    return Text.sha256Hex(identity).substring(0, 16);
  }
}
