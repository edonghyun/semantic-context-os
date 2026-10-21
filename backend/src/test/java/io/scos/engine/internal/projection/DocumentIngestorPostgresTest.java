package io.scos.engine.internal.projection;

import static io.scos.engine.internal.projection.IngestSamples.BODY_A;
import static io.scos.engine.internal.projection.IngestSamples.BODY_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** prune의 유닛·키워드·벡터 삭제가 한 트랜잭션이다(ADR-0018 §6): 색인 삭제 실패 시 유닛도 남고, 재시도 뒤 고아 색인 행이 0이다. */
@Tag("db")
@DisplayName("prune의 유닛·키워드·벡터 삭제는 한 트랜잭션이다(ADR-0018 §6)")
class DocumentIngestorPostgresTest {
  static final SpaceId S = new SpaceId("legacy");
  static final String SRC = "slack-synthetic";
  DataSource ds = PostgresSupport.dataSource();
  JdbcClient jdbc = JdbcClient.create(ds);

  /** 삭제만 주입 실패시키는 키워드 색인 데코레이터. 실패는 이름 있는 메서드로만 켠다. */
  static final class FailingDeleteKeywordIndex implements KeywordIndex {
    final KeywordIndex delegate;
    private volatile boolean failDelete;

    FailingDeleteKeywordIndex(KeywordIndex delegate) {
      this.delegate = delegate;
    }

    void deletesFail() {
      failDelete = true;
    }

    void deletesRecover() {
      failDelete = false;
    }

    @Override
    public IndexCapabilities capabilities() {
      return delegate.capabilities();
    }

    @Override
    public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
      return delegate.search(space, text, k, filter);
    }

    @Override
    public void upsert(SpaceId space, List<IndexedText> units) {
      delegate.upsert(space, units);
    }

    @Override
    public void delete(SpaceId space, List<String> unitIds) {
      if (failDelete) throw new IllegalStateException("injected keyword delete failure");
      delegate.delete(space, unitIds);
    }
  }

  FailingDeleteKeywordIndex keywords =
      new FailingDeleteKeywordIndex(PostgresTestAdapters.keywordIndex(ds));
  UnitStore units = PostgresTestAdapters.unitStore(ds);
  DocumentIngestor ingestor =
      new DocumentIngestor(
          new TransactionTemplate(new DataSourceTransactionManager(ds)),
          units,
          keywords,
          PostgresTestAdapters.vectorIndex(ds),
          PostgresTestAdapters.spaceMeta(ds),
          new RecordingEmbedder(),
          new IngestPipeline(new JtokkitTokenizer()),
          new MutableClock(Instant.parse("2026-10-14T00:00:00Z")));

  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  static SourceDocument doc(String id, String body) {
    return new SourceDocument(
        SRC,
        id,
        "slack://" + SRC + "/" + id,
        "제목 " + id,
        body,
        "2026-10-01T00:00:00.000Z",
        "2026-10-13T00:00:00.000Z");
  }

  int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE space_id = :s")
        .param("s", S.value())
        .query(Integer.class)
        .single();
  }

  /** a(유닛 2)와 b(유닛 1)를 투영해 둔다. */
  void givenTwoDocsProjected() {
    sync(doc("a", BODY_A), doc("b", BODY_B));
    int stored = units.listAll(S).size();
    if (stored != 3) throw new IllegalStateException("given 준비 실패: 유닛 3이어야 한다 — " + stored);
  }

  /** b를 현존 목록에서 빼 prune한다. 키워드 삭제 실패(IllegalStateException)로 끝나지 않으면 준비가 틀렸다. */
  void pruneFailingOnKeywordDelete() {
    try {
      sync(doc("a", BODY_A));
    } catch (IllegalStateException failed) {
      return;
    }
    throw new IllegalStateException("given 준비 실패: 키워드 삭제 실패로 prune이 실패해야 한다");
  }

  void sync(SourceDocument... docs) {
    Set<String> present =
        Arrays.stream(docs).map(SourceDocument::documentId).collect(Collectors.toSet());
    ingestor.sync(S, SRC, List.of(docs), present);
  }

  List<String> storedDocumentIds() {
    return units.listAll(S).stream().map(ContextUnit::documentId).toList();
  }

  @Nested
  @DisplayName("a·b를 투영한 뒤 b를 빼는 prune에서 키워드 삭제가 실패하면")
  class FailedPrune {
    @BeforeEach
    void givenKeywordDeleteFails() {
      givenTwoDocsProjected();
      keywords.deletesFail();
    }

    @Test
    @DisplayName("주입한 실패가 그대로 전파된다")
    void propagatesFailure() {
      // when·then
      assertThatThrownBy(() -> sync(doc("a", BODY_A)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected keyword delete failure");
    }

    @Test
    @DisplayName("빠진 문서 b의 유닛이 남는다")
    void keepsPrunedUnits() {
      // when
      pruneFailingOnKeywordDelete();

      // then
      assertThat(storedDocumentIds()).contains("b");
    }

    @Test
    @DisplayName("키워드 행도 그대로 남는다")
    void keepsIndexRows() {
      // when
      pruneFailingOnKeywordDelete();

      // then
      assertThat(count("unit_texts")).isEqualTo(3);
    }

    @Test
    @DisplayName("벡터 행도 그대로 남는다")
    void keepsVectorRows() {
      // when
      pruneFailingOnKeywordDelete();

      // then
      assertThat(count("unit_vectors")).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("키워드 삭제 실패로 끝난 prune을 삭제가 회복된 뒤 다시 하면")
  class RetriedPrune {
    @BeforeEach
    void givenPruneFailedThenRecovered() {
      givenTwoDocsProjected();
      keywords.deletesFail();
      pruneFailingOnKeywordDelete();
      keywords.deletesRecover();
    }

    @Test
    @DisplayName("a의 유닛만 남는다")
    void keepsOnlyPresentDocument() {
      // when
      sync(doc("a", BODY_A));

      // then
      assertThat(storedDocumentIds()).containsOnly("a");
    }

    @Test
    @DisplayName("고아 키워드 행이 없다")
    void leavesNoOrphanRows() {
      // when
      sync(doc("a", BODY_A));

      // then
      assertThat(count("unit_texts")).isEqualTo(2);
    }

    @Test
    @DisplayName("고아 벡터 행이 없다")
    void leavesNoOrphanVectorRows() {
      // when
      sync(doc("a", BODY_A));

      // then
      assertThat(count("unit_vectors")).isEqualTo(2);
    }
  }
}
