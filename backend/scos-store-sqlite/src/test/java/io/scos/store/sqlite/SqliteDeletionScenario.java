package io.scos.store.sqlite;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.springframework.dao.DataAccessException;

final class SqliteDeletionScenario implements AutoCloseable {
  private static final SpaceId SPACE = new SpaceId("a");
  private static final List<String> IDS = List.of("u1", "u2");
  private final Path file;
  private final SqliteIndex index;

  SqliteDeletionScenario(Path directory) {
    file = directory.resolve("index.sqlite");
    index = SqliteIndex.open(file);
  }

  void givenIndexedDocument() {
    List<ContextUnit> units =
        IDS.stream().map(id -> unit(id).inDocument("doc").source("fixture").build()).toList();
    index.maintenance().upsert(SPACE, units);
    index
        .keywords()
        .upsert(
            SPACE,
            IDS.stream().map(id -> new IndexedText(id, "doc", "검색", "검색 안내", "/", false)).toList());
    index
        .vectors()
        .upsert(
            SPACE,
            IDS.stream()
                .map(id -> new IndexedUnit(id, "doc", "/", false, new double[] {1, 0}))
                .toList());
    if (!vectorIds().equals(IDS) || !unitIds().equals(IDS) || !keywordIds().equals(IDS))
      throw new IllegalStateException("given 준비 실패: 유닛과 두 색인에 문서가 있어야 한다");
  }

  void givenFailureDuringVectorDeletion() throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_vector_delete AFTER DELETE ON vectors
          WHEN OLD.unit_id = 'u2'
          BEGIN SELECT RAISE(FAIL, 'injected vector delete failure'); END
          """);
    }
  }

  DataAccessException deleteVectorsExpectingFailure() {
    try {
      index.vectors().delete(SPACE, IDS);
    } catch (DataAccessException failure) {
      return failure;
    }
    throw new IllegalStateException("벡터 삭제가 주입한 장애를 무시했다");
  }

  DataAccessException deleteDocumentExpectingFailure() {
    try {
      index
          .transactions()
          .executeWithoutResult(
              status -> {
                List<String> removed =
                    index.maintenance().deleteByDocument(SPACE, "fixture", "doc", List.of());
                index.keywords().delete(SPACE, removed);
                index.vectors().delete(SPACE, removed);
              });
    } catch (DataAccessException failure) {
      return failure;
    }
    throw new IllegalStateException("문서 삭제가 주입한 장애를 무시했다");
  }

  List<String> vectorIds() {
    return List.copyOf(index.vectors().vectorsOf(SPACE, AccessScope.singleUser(), IDS).keySet());
  }

  List<String> unitIds() {
    return index.units().getMany(SPACE, AccessScope.singleUser(), IDS).stream()
        .map(ContextUnit::id)
        .toList();
  }

  List<String> keywordIds() {
    return index
        .keywords()
        .search(SPACE, "검색", 10, new SearchFilter(false, null, AccessScope.singleUser()))
        .stream()
        .map(ScoredRef::unitId)
        .toList();
  }

  @Override
  public void close() {
    index.close();
  }
}
