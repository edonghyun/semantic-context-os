package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.ContextEngine;
import io.scos.engine.ContextIngestorBuilder;
import io.scos.engine.SearchQuery;
import io.scos.engine.SearchResult;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SQLite 어댑터 위 엔진 종단")
class SqliteEngineTest {
  private static final Duration WAIT = Duration.ofSeconds(2);
  private static final SpaceId SPACE = SpaceId.LEGACY;
  private static final String SRC = "docs-synthetic";
  private static final String BODY = "# 검색 안내\n\n검색 강도는 낮음, 보통, 높음 중에서 고른다. 높을수록 더 넓게 찾는다.\n";

  private static void ingest(SqliteIndex index, String docId, String body) {
    SourceDocument doc =
        new SourceDocument(
            SRC,
            docId,
            "docs://" + SRC + "/" + docId,
            "검색 문서 " + docId,
            body,
            "2026-10-01T00:00:00.000Z",
            "2026-10-13T00:00:00.000Z");
    new ContextIngestorBuilder()
        .maintenance(index.maintenance())
        .keywords(index.keywords())
        .vectors(index.vectors())
        .meta(index.meta())
        .keywordOnly()
        .cache(index.embeddingCache())
        .transactions(index.transactions())
        .build()
        .ingest(SPACE, SRC, List.of(doc), Set.of(docId));
  }

  private static SearchResult search(SqliteIndex index, String text) {
    ContextEngine engine =
        ContextEngine.builder()
            .units(index.units())
            .unrestricted(index.maintenance())
            .keywords(index.keywords())
            .vectors(index.vectors())
            .keywordOnly()
            .lexicalExecutor(Runnable::run)
            .build();
    return engine.search(Principal.singleUser(), SearchQuery.of(text));
  }

  @Test
  @DisplayName("SQLite에 넣은 한국어 문서를 다시 연 뒤에도 키워드로 찾는다")
  void persistsAcrossReopen(@TempDir Path dir) {
    // given
    Path file = dir.resolve("index.sqlite");
    try (SqliteIndex first = SqliteIndex.open(file)) {
      ingest(first, "d1", BODY);
    }

    // when
    SearchResult result;
    try (SqliteIndex second = SqliteIndex.open(file)) {
      result = search(second, "검색 강도");
    }

    // then
    assertThat(result.hits()).isNotEmpty();
    assertThat(result.hits().get(0).documentId()).isEqualTo("d1");
  }

  @Nested
  @DisplayName("한 연결이 쓰기 트랜잭션을 연 채 멈춰 있으면")
  class WhileWriterHoldsTransaction {
    private final CountDownLatch writing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private SqliteIndex writer;
    private SqliteIndex reader;
    private Future<?> write;

    @BeforeEach
    void givenOpenWriteTransaction(@TempDir Path dir) throws InterruptedException {
      Path file = dir.resolve("index.sqlite");
      writer = SqliteIndex.open(file);
      reader = SqliteIndex.open(file);
      ingest(writer, "d1", BODY);
      write =
          pool.submit(
              () ->
                  writer
                      .transactions()
                      .executeWithoutResult(
                          status -> {
                            writer
                                .keywords()
                                .upsert(
                                    SPACE,
                                    List.of(
                                        new IndexedText(
                                            "u-new", "d2", "새 문서", "검색 강도 새 내용", "/", false)));
                            writing.countDown();
                            await(release);
                          }));
      assertThat(writing.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void finishWrite() throws Exception {
      release.countDown();
      write.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      pool.shutdownNow();
      writer.close();
      reader.close();
    }

    @Test
    @DisplayName("다른 SqliteIndex가 검색에 답하고 커밋 전의 이전 결과만 본다")
    void readsWhileWriting() throws Exception {
      // given: 쓰기 트랜잭션이 멈춰 있다

      // when
      SearchResult result =
          pool.submit(() -> search(reader, "검색 강도")).get(WAIT.toSeconds(), TimeUnit.SECONDS);

      // then
      assertThat(result.hits()).extracting("documentId").containsExactly("d1");
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("래치 대기 초과");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
