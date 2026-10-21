package io.scos.engine.internal.projection;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import io.scos.store.postgres.PostgresTestAdapters;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 적재 유스케이스: 저장·키워드·벡터가 함께 커밋되거나 함께 롤백된다(불변식 2, ADR-0006·0007 "원자성은 어댑터 조합"). */
@Tag("db")
@DisplayName("유닛 적재는 저장·키워드·벡터를 함께 커밋하거나 함께 롤백한다(불변식 2)")
class UnitImporterTest {
  static final SpaceId S = new SpaceId("legacy");
  DataSource ds = PostgresSupport.dataSource();
  UnitStore units = PostgresTestAdapters.unitStore(ds);
  KeywordIndex keywords = PostgresTestAdapters.keywordIndex(ds);
  VectorIndex vectors = PostgresTestAdapters.vectorIndex(ds);
  TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

  static final SearchFilter INCLUDE_ARCHIVED = new SearchFilter(true, null);
  static final UnitStore.Range FIRST_PAGE = new UnitStore.Range(0, 10);
  static final double[] QUERY_VECTOR = {1, 0};

  /** 모든 호출을 실제 색인에 넘기되 upsert는 쓴 뒤 실패하는 벡터 색인. */
  static final class FailingUpsertVectorIndex implements VectorIndex {
    final VectorIndex delegate;

    FailingUpsertVectorIndex(VectorIndex delegate) {
      this.delegate = delegate;
    }

    @Override
    public IndexCapabilities capabilities() {
      return delegate.capabilities();
    }

    @Override
    public List<List<ScoredRef>> search(SpaceId s, List<double[]> q, int k, SearchFilter f) {
      return delegate.search(s, q, k, f);
    }

    @Override
    public void upsert(SpaceId s, List<IndexedUnit> u) {
      delegate.upsert(s, u);
      throw new IllegalStateException("vector index down");
    }

    @Override
    public void delete(SpaceId s, List<String> ids) {
      delegate.delete(s, ids);
    }

    @Override
    public Map<String, double[]> vectorsOf(SpaceId s, List<String> ids) {
      return delegate.vectorsOf(s, ids);
    }
  }

  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  List<String> storedIds() {
    return units.listByDocument(S, "d1", FIRST_PAGE).stream().map(ContextUnit::id).toList();
  }

  List<String> keywordHits(SearchFilter filter) {
    return keywords.search(S, "정산", 10, filter).stream().map(ScoredRef::unitId).toList();
  }

  List<String> vectorHits() {
    return vectors.search(S, List.of(QUERY_VECTOR), 10, INCLUDE_ARCHIVED).get(0).stream()
        .map(ScoredRef::unitId)
        .toList();
  }

  @Nested
  @DisplayName("임베딩 있는 u1과 임베딩 없는 archived u2를 적재하면")
  class Imports {
    UnitImporter.Imported importEmbeddedU1AndArchivedU2() {
      return new UnitImporter(tx, units, keywords, vectors)
          .importUnits(
              S,
              List.of(
                  unit("u1").inDocument("d1").content("정산 배치는 매일 자정에 실행된다").embedding(1, 0).build(),
                  unit("u2").inDocument("d1").content("정산 배치 재시도 규칙").archived().build()));
    }

    @Test
    @DisplayName("유닛 수와 벡터 수를 보고한다")
    void reportsCounts() {
      // when
      UnitImporter.Imported imported = importEmbeddedU1AndArchivedU2();

      // then
      assertThat(imported).isEqualTo(new UnitImporter.Imported(2, 1));
    }

    @Test
    @DisplayName("두 유닛을 저장한다")
    void storesUnits() {
      // when
      importEmbeddedU1AndArchivedU2();

      // then
      assertThat(storedIds()).containsExactly("u1", "u2");
    }

    @Test
    @DisplayName("기본 필터의 키워드 검색은 archived를 뺀다")
    void keywordSearchExcludesArchived() {
      // when
      importEmbeddedU1AndArchivedU2();

      // then
      assertThat(keywordHits(SearchFilter.NONE)).containsExactly("u1");
    }

    @Test
    @DisplayName("archived 포함 필터의 키워드 검색은 둘 다 찾는다")
    void keywordSearchIncludesArchivedOnRequest() {
      // when
      importEmbeddedU1AndArchivedU2();

      // then
      assertThat(keywordHits(INCLUDE_ARCHIVED)).containsExactly("u1", "u2");
    }

    @Test
    @DisplayName("임베딩 있는 유닛만 벡터 색인에 들어간다")
    void indexesOnlyEmbeddedVectors() {
      // when
      importEmbeddedU1AndArchivedU2();

      // then
      assertThat(vectorHits()).containsExactly("u1");
    }
  }

  @Nested
  @DisplayName("벡터 색인이 중간에 실패하면(한 트랜잭션)")
  class RollsBackTogether {
    /** 적재가 주입한 벡터 색인 실패(종류·고유 메시지)로 끝났음을 확인한다 — 다른 실패면 아래 롤백 판정이 엉뚱한 원인을 본다. */
    @BeforeEach
    void givenVectorIndexFailedMidImport() {
      UnitImporter importer =
          new UnitImporter(tx, units, keywords, new FailingUpsertVectorIndex(vectors));
      try {
        importer.importUnits(
            S, List.of(unit("u1").inDocument("d1").content("정산 배치").embedding(1, 0).build()));
      } catch (IllegalStateException e) {
        if (!"vector index down".equals(e.getMessage()))
          throw new IllegalStateException("given 준비 실패: 적재가 벡터 색인 실패로 끝나야 한다", e);
        return;
      }
      throw new IllegalStateException("given 준비 실패: 적재가 벡터 색인 실패로 끝나야 한다");
    }

    @Test
    @DisplayName("유닛이 남지 않는다")
    void storesNoUnit() {
      // when
      List<String> stored = storedIds();

      // then
      assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("키워드 색인에 남지 않는다")
    void indexesNoKeyword() {
      // when
      List<String> hits = keywordHits(SearchFilter.NONE);

      // then
      assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("이미 쓴 벡터도 남지 않는다")
    void indexesNoVector() {
      // when
      List<String> hits = vectorHits();

      // then
      assertThat(hits).isEmpty();
    }
  }
}
