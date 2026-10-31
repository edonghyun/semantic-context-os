package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.engine.support.PureFunctions;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.SnapshotScope;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** roots 설정에서 동일한 범위 식별자와 부재 검증 계약을 계산한다. */
@PureFunctions
public final class NotionScopeIdentity {
  private NotionScopeIdentity() {}

  public static SnapshotScope verificationScope(ObjectMapper json, Roots roots) {
    return new SnapshotScope(
        Text.sha256Hex(JsJson.stringify(json(json, roots))),
        Map.of(
            "notion.page",
            ReconciliationKind.PAGE,
            "notion.database",
            ReconciliationKind.CONTAINER,
            "notion.data_source",
            ReconciliationKind.CONTAINER));
  }

  static ObjectNode json(ObjectMapper json, Roots roots) {
    ObjectNode scope = json.createObjectNode();
    scope.set("rootPageIds", json.valueToTree(roots.rootPageIds()));
    scope.put("discoverSharedPages", roots.discoverSharedPages());
    return scope;
  }
}
