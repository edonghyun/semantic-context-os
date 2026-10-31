package io.scos.client.collect;

import io.scos.client.ClientJson;
import io.scos.client.DocumentMetadata;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.store.sqlite.SqliteMigrations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 클라이언트의 `state.sqlite`(WAL): 수집한 원본과 체크포인트. 색인(`index.sqlite`)은 여기서 다시 만들 수 있는 파생물이다. 연결 하나를 쓰고
 * 메서드는 직렬화한다(쓰는 쪽은 잠금 보유자 하나뿐이다).
 */
public final class StateStore implements AutoCloseable {
  private static final String MIGRATIONS = "db/sqlite/state";
  private static final int CHUNK = 500;

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;
  private final SingleConnectionDataSource dataSource;

  private StateStore(SingleConnectionDataSource dataSource) {
    this.dataSource = dataSource;
    jdbc = JdbcClient.create(dataSource);
    transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
  }

  /** 파일을 열고(상위 폴더는 만든다) 스키마를 `user_version`으로 적용한다. 더 새 스키마의 파일은 {@link IllegalStateException}. */
  public static StateStore open(Path file) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      SQLiteConfig config = new SQLiteConfig();
      config.setBusyTimeout(SqliteMigrations.BUSY_MILLIS);
      config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
      SQLiteDataSource dataSource = new SQLiteDataSource(config);
      dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
      new SqliteMigrations(dataSource, MIGRATIONS, "state.sqlite").apply();
      // JDBC 작업이 연결을 반환해도 WAL 수명은 이 저장소가 소유한다.
      return new StateStore(new SingleConnectionDataSource(dataSource.getConnection(), true));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (SQLException e) {
      throw new IllegalStateException("state.sqlite open failed", e);
    }
  }

  /**
   * 페이지의 변경과 체크포인트를 한 트랜잭션으로 반영한다.
   *
   * @return 이 페이지가 닿은 문서 id(삭제된 항목의 문서 포함)
   */
  public synchronized Set<String> commit(String source, CollectionPage page) {
    Set<String> touched = new TreeSet<>();
    inTransaction(
        () -> {
          for (RawMutation mutation : page.mutations()) {
            switch (mutation) {
              case RawMutation.Upsert u -> touched.add(upsert(source, u.item()));
              case RawMutation.Delete d -> delete(source, d.externalId()).ifPresent(touched::add);
            }
          }
          for (String documentId : touched) markPending(source, documentId);
          putCheckpoint(source, page.nextCheckpoint());
        });
    return touched;
  }

  private String upsert(String source, RawItem item) {
    String documentId =
        DocumentMetadata.documentIdOf(item)
            .map(DocumentMetadata::documentId)
            .orElse(item.externalId());
    jdbc.sql(
            """
        INSERT OR REPLACE INTO raw_items
          (source, external_id, document_id, resource_type, source_version,
           source_updated_at, observed_at, content, media_type)
        VALUES (:source, :externalId, :documentId, :resourceType, :version,
                :updatedAt, :observedAt, :content, :mediaType)
        """)
        .param("source", source)
        .param("externalId", item.externalId())
        .param("documentId", documentId)
        .param("resourceType", item.resourceType())
        .param("version", item.sourceVersion())
        .param("updatedAt", item.sourceUpdatedAt().orElse(null), Types.VARCHAR)
        .param("observedAt", item.observedAt().toString())
        .param("content", item.content().map(StateStore::bytesOf).orElse(null), Types.BLOB)
        .param("mediaType", item.content().map(RawContent::mediaType).orElse(ClientJson.MEDIA_TYPE))
        .update();
    return documentId;
  }

  private Optional<String> delete(String source, String externalId) {
    return jdbc.sql(
            """
        DELETE FROM raw_items WHERE source = :source AND external_id = :externalId
        RETURNING document_id
        """)
        .param("source", source)
        .param("externalId", externalId)
        .query(String.class)
        .optional();
  }

  private void markPending(String source, String documentId) {
    jdbc.sql("INSERT OR IGNORE INTO pending_projection (source, document_id) VALUES (:source, :id)")
        .param("source", source)
        .param("id", documentId)
        .update();
  }

  /** 원천들의 state 문서를 모두 투영 대기 목록에 한 트랜잭션으로 넣는다(전체 재투영 — 중간에 끊겨도 다음 run이 잇는다). */
  public synchronized void markAllPending(Collection<String> sources) {
    inTransaction(
        () -> {
          for (String source : sources) {
            jdbc.sql(
                    """
            INSERT OR IGNORE INTO pending_projection (source, document_id)
            SELECT DISTINCT source, document_id FROM raw_items WHERE source = :source
            """)
                .param("source", source)
                .update();
          }
        });
  }

  /** 투영을 기다리는 문서 id. */
  public synchronized Set<String> pendingProjection(String source) {
    return access(
        () ->
            new TreeSet<>(
                jdbc.sql("SELECT document_id FROM pending_projection WHERE source = :source")
                    .param("source", source)
                    .query(String.class)
                    .list()));
  }

  /** 투영을 마친 문서를 대기 목록에서 뺀다. */
  public synchronized void clearPending(String source, Set<String> documentIds) {
    inTransaction(
        () -> {
          for (String id : documentIds) {
            jdbc.sql("DELETE FROM pending_projection WHERE source = :source AND document_id = :id")
                .param("source", source)
                .param("id", id)
                .update();
          }
        });
  }

  private void putCheckpoint(String source, Checkpoint checkpoint) {
    jdbc.sql(
            """
        INSERT OR REPLACE INTO checkpoints (source, schema_version, value_json)
        VALUES (:source, :version, :value)
        """)
        .param("source", source)
        .param("version", checkpoint.schemaVersion())
        .param("value", checkpoint.valueJson())
        .update();
  }

  public synchronized Optional<Checkpoint> checkpoint(String source) {
    return access(
        () ->
            jdbc.sql("SELECT schema_version, value_json FROM checkpoints WHERE source = :source")
                .param("source", source)
                .query(
                    (rs, i) ->
                        new Checkpoint(rs.getInt("schema_version"), rs.getString("value_json")))
                .optional());
  }

  /** 이 원천에 남은 항목이 속한 모든 문서 id(색인에 살아 있어야 할 문서). */
  public synchronized Set<String> documentIds(String source) {
    return access(
        () ->
            new TreeSet<>(
                jdbc.sql("SELECT DISTINCT document_id FROM raw_items WHERE source = :source")
                    .param("source", source)
                    .query(String.class)
                    .list()));
  }

  /** 지정한 문서·자원 종류의 항목. 내용은 저장한 미디어 타입의 Inline으로, 메타데이터는 문서 id만 돌려준다. */
  public synchronized List<RawItem> items(
      String source, Set<String> documentIds, Set<String> resourceTypes) {
    if (documentIds.isEmpty() || resourceTypes.isEmpty()) return List.of();
    return access(
        () -> {
          List<RawItem> items = new ArrayList<>();
          List<String> ids = List.copyOf(documentIds);
          for (int from = 0; from < ids.size(); from += CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            items.addAll(
                jdbc.sql(
                        """
            SELECT external_id, document_id, resource_type, source_version, source_updated_at,
                   observed_at, content, media_type FROM raw_items
            WHERE source = :source AND document_id IN (:ids) AND resource_type IN (:types)
            """)
                    .param("source", source)
                    .param("ids", chunk)
                    .param("types", resourceTypes)
                    .query((rs, i) -> read(rs))
                    .list());
          }
          return items;
        });
  }

  public synchronized Optional<String> content(String source, String externalId) {
    return access(
        () ->
            jdbc.sql("SELECT content FROM raw_items WHERE source = :source AND external_id = :id")
                .param("source", source)
                .param("id", externalId)
                .query(
                    (rs, i) ->
                        Optional.ofNullable(rs.getBytes("content"))
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8)))
                .optional()
                .orElse(Optional.empty()));
  }

  private RawItem read(ResultSet rs) throws SQLException {
    byte[] bytes = rs.getBytes("content");
    RawItem item =
        new RawItem(
            rs.getString("external_id"),
            rs.getString("resource_type"),
            rs.getString("source_version"),
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(rs.getString("source_updated_at")),
            Instant.parse(rs.getString("observed_at")),
            DocumentMetadata.of(rs.getString("document_id")).toJson(),
            Optional.empty());
    return bytes == null
        ? item
        : item.withContent(
            new RawContent.Inline(
                rs.getString("media_type"), new String(bytes, StandardCharsets.UTF_8)));
  }

  private static byte[] bytesOf(RawContent content) {
    return switch (content) {
      case RawContent.Inline i -> i.valueJson().getBytes(StandardCharsets.UTF_8);
      case RawContent.Blob b -> b.data().orElse(new byte[0]);
    };
  }

  private void inTransaction(Runnable work) {
    access(
        () -> {
          transactions.executeWithoutResult(status -> work.run());
          return null;
        });
  }

  private <T extends @Nullable Object> T access(Supplier<T> work) {
    try {
      return work.get();
    } catch (DataAccessException | TransactionException e) {
      throw new Failure(e);
    }
  }

  /** state 실패는 파생 색인의 투영 실패와 달리 원천 수집을 중단해야 한다. */
  static final class Failure extends IllegalStateException {
    private Failure(RuntimeException cause) {
      super("state.sqlite access failed", cause);
    }
  }

  @Override
  public synchronized void close() {
    dataSource.close();
  }
}
