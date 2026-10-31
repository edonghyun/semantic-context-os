package io.scos.server;

import io.scos.boot.AccessProperties;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.spi.access.AclStore;
import io.scos.spi.embedding.EmbeddingGeneration;
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
 * 유닛 JSON(ContextUnit 배열, 하네스 documents.json의 `units`) → 적재 유스케이스. 배열 순서가 적재 순서(seq)다. 파일 형식 오류는
 * IllegalArgumentException(기동 실패), 저장 오류는 유스케이스가 전파한다.
 */
public final class UnitJsonImport {
  /** 필드가 없을 때의 값(파일을 만든 쪽의 기본값과 같다). */
  private static final String DEFAULT_PARTITION = "/knowledge";

  private static final int DEFAULT_IMPORTANCE = 5;
  private static final String DEFAULT_TIER = "raw";

  private final UnitImporter importer;
  private final ObjectMapper json;
  private final AccessProperties access;
  private final AclStore acls;

  public UnitJsonImport(
      UnitImporter importer, ObjectMapper json, AccessProperties access, AclStore acls) {
    this.importer = importer;
    this.json = json;
    this.access = access;
    this.acls = acls;
  }

  /** declared: 파일의 벡터를 만든 임베딩 세대(`--embedding-generation`) — 벡터가 있으면 유스케이스가 요구한다. */
  public UnitImporter.Imported importFile(
      Path file, SpaceId space, Optional<EmbeddingGeneration> declared) throws IOException {
    JsonNode root = json.readTree(Files.readString(file));
    JsonNode array = root.isArray() ? root : root.get("units");
    if (array == null || !array.isArray())
      throw new IllegalArgumentException("units 배열이 없습니다: " + file);
    List<ContextUnit> units = new ArrayList<>();
    for (JsonNode n : array) units.add(toUnit(n));
    if (units.stream().anyMatch(unit -> unit.tier() == Tier.COMPACTED)
        && (access.mode() == AccessProperties.Mode.MULTI_USER || acls.hasAny()))
      throw new IllegalArgumentException("다중 사용자 모드 또는 문서 ACL이 있는 DB에는 압축 유닛을 가져올 수 없습니다");
    return importer.importUnits(space, units, declared);
  }

  /** ContextUnit JSON 하나 → 유닛(이전 엔진 DB에서 내보낸 `units.json`과 같은 형식). */
  private static ContextUnit toUnit(JsonNode n) {
    JsonNode p = n.path("provenance");
    return new ContextUnit(
        n.path("id").asString(),
        n.path("partition").asString(DEFAULT_PARTITION),
        n.path("title").asString(""),
        n.path("content").asString(""),
        n.path("contentHash").asString(""),
        n.path("tokens").asInt(0),
        Tier.fromWire(n.path("tier").asString(DEFAULT_TIER)),
        new Provenance(
            p.path("sourceId").asString(""),
            p.path("documentId").asString(),
            p.path("uri").asString(""),
            p.path("capturedAt").asString("")),
        n.path("importance").asInt(DEFAULT_IMPORTANCE),
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
