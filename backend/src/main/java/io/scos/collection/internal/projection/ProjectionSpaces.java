package io.scos.collection.internal.projection;

import io.scos.collection.internal.SourceInstance;
import io.scos.spi.store.SpaceId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 소스 → 공간(TS projection-space.ts, A4): 명시 매핑(SCOS_SOURCE_SPACES JSON 객체) 없으면 기본 공간(SCOS_SPACE_ID).
 * Slack DM(im/mpim)과 Tiro(회의록)는 명시 매핑 없이는 투영을 거부한다(A5, TS :18-35). TS는 SCOS_SPACE_ID만 있어도 받지만 여기서는
 * 기본 공간이 미설정과 구분되지 않아(기본값 legacy) 소스별 매핑을 요구한다.
 */
public final class ProjectionSpaces {
  private final Map<String, SpaceId> explicit;
  private final SpaceId defaultSpace;
  private final ObjectMapper json;

  public ProjectionSpaces(Map<String, SpaceId> explicit, SpaceId defaultSpace, ObjectMapper json) {
    this.explicit = Map.copyOf(explicit);
    this.defaultSpace = defaultSpace;
    this.json = json;
  }

  /** `{"<sourceId>": "<spaceId>", …}`. 비면 매핑 없음. */
  public static ProjectionSpaces fromJson(
      String sourceSpacesJson, SpaceId defaultSpace, ObjectMapper json) {
    Map<String, SpaceId> map = new LinkedHashMap<>();
    if (sourceSpacesJson != null && !sourceSpacesJson.isBlank()) {
      JsonNode node = json.readTree(sourceSpacesJson);
      if (!node.isObject())
        throw new IllegalArgumentException("SCOS_SOURCE_SPACES must be a JSON object");
      for (var e : node.properties()) map.put(e.getKey(), new SpaceId(e.getValue().asString()));
    }
    return new ProjectionSpaces(map, defaultSpace, json);
  }

  /** 투영이 쓰는 공간 전부(기본 공간 + 명시 매핑의 공간). */
  public Set<SpaceId> spaces() {
    Set<SpaceId> all = new LinkedHashSet<>(explicit.values());
    all.add(defaultSpace);
    return Set.copyOf(all);
  }

  public SpaceId resolve(SourceInstance instance) {
    SpaceId mapped = explicit.get(instance.id().value());
    if (mapped != null) return mapped;
    if (instance.kind().equals("tiro"))
      throw new IllegalStateException(
          "Tiro source requires an explicit projection space (set SCOS_SOURCE_SPACES): "
              + instance.id().value());
    if (instance.kind().equals("slack")) {
      JsonNode types = json.readTree(instance.config().valueJson()).path("conversationTypes");
      for (JsonNode t : types)
        if (t.isString() && (t.asString().equals("im") || t.asString().equals("mpim")))
          throw new IllegalStateException(
              "Slack DM source requires an explicit projection space: " + instance.id().value());
    }
    return defaultSpace;
  }
}
