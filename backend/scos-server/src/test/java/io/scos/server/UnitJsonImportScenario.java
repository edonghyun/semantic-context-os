package io.scos.server;

import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 공통 계약을 통과한 메모리 어댑터와 실제 적재 유스케이스로 가져오기 전 거부를 관측한다. */
final class UnitJsonImportScenario {
  private static final SpaceId SPACE = new SpaceId("legacy");
  private static final EmbeddingGeneration GENERATION =
      EmbeddingGeneration.parse("test:import:2:title+aliases+content");
  private static final String MIXED =
      """
      [{"id":"existing","title":"덮어쓴 제목","provenance":{"sourceId":"fixture","documentId":"doc-existing"}},
       {"id":"new-raw","provenance":{"sourceId":"fixture","documentId":"doc-new"}},
       {"id":"summary","tier":"compacted","provenance":{"sourceId":"fixture","documentId":"doc-summary"}}]
      """;
  private static final String RAW =
      """
      {"units":[{"id":"new-raw","provenance":{"sourceId":"fixture","documentId":"doc-new"}}]}
      """;
  private final MemoryAclStore acls = new MemoryAclStore();
  private final MemoryUnitStore units = new MemoryUnitStore(acls);
  private final MemoryVectorIndex vectors = new MemoryVectorIndex(acls);
  private final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors);
  private final UnitJsonImport jsonImport;
  private final Path mixed;

  UnitJsonImportScenario(Path dir, Mode mode) throws IOException {
    var importer =
        new UnitImporter(
            new DirectTransactions(),
            maintenance,
            new MemoryKeywordIndex(acls),
            vectors,
            new MemorySpaceMeta(),
            GENERATION);
    importer.importUnits(
        SPACE, List.of(ContextUnits.unit("existing").titled("기존 제목").build()), Optional.empty());
    if (storedUnits().size() != 1) throw new IllegalStateException("given 준비 실패: 기존 유닛이 있어야 한다");
    jsonImport =
        new UnitJsonImport(
            importer,
            JsonMapper.builder().build(),
            new AccessProperties(mode, null, List.of(), Map.of()),
            acls);
    mixed = Files.writeString(dir.resolve("mixed.json"), MIXED);
  }

  void givenAclInAnotherSpace(DocumentAcl.Status status) {
    acls.put(
        new SpaceId("other"),
        List.of(
            new DocumentAcl("unrelated", status, Set.of(), Instant.parse("2026-10-27T00:00:00Z"))));
  }

  UnitImporter.Imported importMixed() throws IOException {
    return jsonImport.importFile(mixed, SPACE, Optional.empty());
  }

  UnitImporter.Imported importRaw() throws IOException {
    Path raw = Files.writeString(mixed.resolveSibling("raw.json"), RAW);
    return jsonImport.importFile(raw, SPACE, Optional.empty());
  }

  List<ContextUnit> storedUnits() {
    return maintenance.listAll(SPACE);
  }

  /** 트랜잭션 원자성을 흉내 내지 않는다. 테스트는 트랜잭션 진입 전 전체 거부만 검증한다. */
  private static final class DirectTransactions extends TransactionTemplate {
    @Override
    public <T> T execute(TransactionCallback<T> action) {
      return TransactionOperations.withoutTransaction().execute(action);
    }
  }
}
