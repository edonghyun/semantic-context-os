package io.scos.demo;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.Reranker;
import io.scos.engine.internal.search.SearchSettings;
import io.scos.server.UnitJsonImport;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Quickstart와 같은 순서로 데모 코퍼스를 PostgreSQL(Testcontainers)에 넣고 검색한다: units.json에 해싱 임베딩을 붙이고(demo
 * embed_units.py와 같은 입력 {@code title\ncontent}) → 제품 `import` 유스케이스(UnitJsonImport) →
 * QuestionSearch(재정렬 끔, 질의도 같은 해싱 임베더). 만들 때 DB를 비운다.
 */
@Tag("db")
final class DemoCorpusDatabase {
  static final SpaceId SPACE = new SpaceId("demo");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DataSource ds;
  private final HashingEmbedder embedder = new HashingEmbedder();
  private final UnitImporter.Imported imported;

  DemoCorpusDatabase(DemoCorpus corpus) {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    UnitImporter importer =
        new UnitImporter(
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            PostgresTestAdapters.unitStore(ds),
            PostgresTestAdapters.keywordIndex(ds),
            PostgresTestAdapters.vectorIndex(ds));
    try {
      imported = new UnitJsonImport(importer, JSON).importFile(embeddedUnitsFile(corpus), SPACE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** demo/embedder/embed_units.py와 같은 변환: 유닛마다 {@code embedding}을 붙인 임시 적재 파일. */
  private Path embeddedUnitsFile(DemoCorpus corpus) throws IOException {
    JsonNode file = JSON.readTree(corpus.committed(DemoCorpus.UNITS_FILE));
    for (JsonNode unit : file.get("units")) {
      double[] vector =
          embedder
              .embed(List.of(unit.get("title").asString() + "\n" + unit.get("content").asString()))
              .getFirst();
      ((ObjectNode) unit).putPOJO("embedding", vector);
    }
    Path out = Files.createTempFile("scos-demo-units-", ".json");
    out.toFile().deleteOnExit();
    Files.writeString(out, JSON.writeValueAsString(file));
    return out;
  }

  UnitImporter.Imported imported() {
    return imported;
  }

  long documents() {
    return JdbcClient.create(ds)
        .sql("SELECT count(DISTINCT document_id) FROM units WHERE space_id = ?")
        .param(SPACE.value())
        .query(Long.class)
        .single();
  }

  /** search_context와 같은 레시피(키워드·벡터 RRF, 재정렬 없음)의 상위 문서 — 같은 문서는 처음 나온 순위만. */
  List<String> topDocuments(String query, int limit) {
    return new QuestionSearch(
            PostgresTestAdapters.unitStore(ds),
            PostgresTestAdapters.keywordIndex(ds),
            PostgresTestAdapters.vectorIndex(ds),
            embedder,
            Reranker.DISABLED,
            SearchSettings.DEFAULT,
            CLOCK,
            TestMetrics.search(),
            TestExecutors.CALLER_THREAD)
        .search(AccessScope.singleUser(), SPACE, query).candidates().stream()
            .map(c -> c.unit().documentId())
            .distinct()
            .limit(limit)
            .toList();
  }
}
