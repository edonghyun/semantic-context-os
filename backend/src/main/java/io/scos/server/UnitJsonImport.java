package io.scos.server;

import io.scos.engine.internal.projection.UnitImporter;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Provenance;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 유닛 JSON(TS ContextUnit 배열, 하네스 documents.json의 `units`) → 적재 유스케이스. 배열 순서가 적재 순서(seq)다. 파일 형식 오류는
 * IllegalArgumentException(기동 실패), 저장 오류는 유스케이스가 전파한다.
 */
public final class UnitJsonImport {
  private final UnitImporter importer;
  private final ObjectMapper json;

  public UnitJsonImport(UnitImporter importer, ObjectMapper json) {
    this.importer = importer;
    this.json = json;
  }

  public UnitImporter.Imported importFile(Path file, SpaceId space) throws IOException {
    JsonNode root = json.readTree(Files.readString(file));
    JsonNode array = root.isArray() ? root : root.get("units");
    if (array == null || !array.isArray())
      throw new IllegalArgumentException("units 배열이 없습니다: " + file);
    List<ContextUnit> units = new ArrayList<>();
    for (JsonNode n : array) units.add(toUnit(n));
    return importer.importUnits(space, units);
  }

  /** TS ContextUnit JSON 하나 → Java 유닛(F9 이전 도구도 같은 규칙으로 TS 엔진 DB의 `units.json`을 읽는다). */
  public static ContextUnit toUnit(JsonNode n) {
    JsonNode p = n.path("provenance");
    return new ContextUnit(
        n.path("id").asString(),
        n.path("partition").asString("/knowledge"),
        n.path("title").asString(""),
        n.path("content").asString(""),
        n.path("contentHash").asString(""),
        n.path("tokens").asInt(0),
        Tier.fromWire(n.path("tier").asString("raw")),
        new Provenance(
            p.path("sourceId").asString(""),
            p.path("documentId").asString(),
            p.path("uri").asString(""),
            p.path("capturedAt").asString("")),
        n.path("importance").asInt(5),
        text(n, "validFrom"),
        text(n, "invalidatedAt"),
        strings(n.get("links")),
        strings(n.get("tags")),
        strings(n.get("aliases")),
        n.hasNonNull("embedding") ? Optional.of(doubles(n.get("embedding"))) : Optional.empty(),
        strings(n.get("compactedFrom")));
  }

  private static Optional<String> text(JsonNode n, String field) {
    return n.hasNonNull(field) ? Optional.of(n.get(field).asString()) : Optional.empty();
  }

  private static List<String> strings(JsonNode arr) {
    if (arr == null || !arr.isArray()) return List.of();
    List<String> out = new ArrayList<>();
    arr.forEach(x -> out.add(x.asString()));
    return out;
  }

  private static double[] doubles(JsonNode arr) {
    double[] v = new double[arr.size()];
    for (int i = 0; i < v.length; i++) v[i] = arr.get(i).asDouble();
    return v;
  }
}
