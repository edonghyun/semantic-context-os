package io.scos.client.collect;

import io.scos.client.ClientJson;
import io.scos.client.ContentHash;
import io.scos.client.DocumentMetadata;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** 실제 state와 캡처를 쓰며 SQL 오류는 SQLite 트리거로 일으킨다. */
final class StateStoreScenario implements AutoCloseable {
  private final Path file;
  private final StateStore store;

  StateStoreScenario(Path root) {
    file = root.resolve("state.sqlite");
    store = StateStore.open(file);
  }

  void givenRejectedCheckpointWrite() throws SQLException {
    store.commit("claude-memory", page(new Checkpoint(1, "{\"files\":{}}")));
    rejectCheckpointWrites();
  }

  void givenRejectedReplacementPageWrite() throws SQLException {
    store.commit(
        "claude-memory",
        page(
            new Checkpoint(1, "{\"files\":{}}"),
            new RawMutation.Upsert(memoryItem()),
            new RawMutation.Upsert(memoryItem("d/b.md", "삭제 전 메모"))));
    execute("DELETE FROM pending_projection");
    rejectCheckpointWrites();
  }

  void commitReplacementPage() {
    store.commit(
        "claude-memory",
        page(
            new Checkpoint(1, "{\"files\":{\"d/a.md\":{}}}"),
            new RawMutation.Upsert(memoryItem("d/a.md", "수정한 메모")),
            new RawMutation.Delete(
                "d/b.md",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Instant.parse("2026-10-28T00:00:00Z"))));
  }

  Optional<String> content(String externalId) {
    return store.content("claude-memory", externalId);
  }

  void givenPendingDocuments() throws SQLException {
    commitMemoryPage();
    execute(
        "INSERT INTO raw_items SELECT source, 'd/b.md', 'memory/d/b.md', resource_type,"
            + " source_version, source_updated_at, observed_at, content, media_type FROM raw_items");
    execute("INSERT INTO pending_projection VALUES ('claude-memory', 'memory/d/b.md')");
  }

  void givenRejectedPendingMark() throws SQLException {
    commitMemoryPage();
    store.commit(
        "second-memory", page(new Checkpoint(1, "{}"), new RawMutation.Upsert(memoryItem())));
    execute("DELETE FROM pending_projection");
    execute(
        "CREATE TRIGGER reject_mark BEFORE INSERT ON pending_projection"
            + " WHEN NEW.source = 'second-memory'"
            + " BEGIN SELECT RAISE(ABORT, 'synthetic mark failure'); END");
  }

  void givenRejectedPendingClear() throws SQLException {
    givenPendingDocuments();
    execute(
        "CREATE TRIGGER reject_clear BEFORE DELETE ON pending_projection"
            + " WHEN OLD.document_id = 'memory/d/b.md'"
            + " BEGIN SELECT RAISE(ABORT, 'synthetic clear failure'); END");
  }

  void markAllPending() {
    store.markAllPending(List.of("claude-memory", "second-memory"));
  }

  void clearPending() {
    store.clearPending("claude-memory", new TreeSet<>(Set.of("memory/d/a.md", "memory/d/b.md")));
  }

  void commitMemoryPage() {
    store.commit(
        "claude-memory",
        page(
            new Checkpoint(1, "{\"files\":{\"d/a.md\":{}}}"),
            new RawMutation.Upsert(memoryItem())));
  }

  Set<String> documentIds() {
    return store.documentIds("claude-memory");
  }

  List<RawItem> storedMemoryItems() {
    return store.items("claude-memory", Set.of("memory/d/a.md"), Set.of("claude.memory.file"));
  }

  void givenMemoryMetadata(String metadata) {
    store.commit(
        "claude-memory",
        page(
            new Checkpoint(1, "{}"),
            new RawMutation.Upsert(memoryItem("d/a.md", "합성 메모", metadata))));
  }

  Set<String> pendingProjection() {
    return store.pendingProjection("claude-memory");
  }

  Optional<Checkpoint> checkpoint() {
    return store.checkpoint("claude-memory");
  }

  private RawItem memoryItem() {
    return memoryItem("d/a.md", "합성 메모");
  }

  private RawItem memoryItem(String externalId, String text) {
    return memoryItem(externalId, text, DocumentMetadata.of("memory/" + externalId).toJson());
  }

  private RawItem memoryItem(String externalId, String text, String metadata) {
    String version = new ContentHash().sha256(text.getBytes(StandardCharsets.UTF_8));
    return new RawItem(
            externalId,
            "claude.memory.file",
            version,
            Optional.empty(),
            Optional.empty(),
            Optional.of("2026-10-27T10:00:00.000Z"),
            Instant.parse("2026-10-28T00:00:00Z"),
            metadata,
            Optional.empty())
        .withContent(
            new LocalContentCapture()
                .json(
                    "application/json",
                    ClientJson.MAPPER.writeValueAsString(Map.of("text", text))));
  }

  private CollectionPage page(Checkpoint next, RawMutation... mutations) {
    return new CollectionPage(List.of(mutations), List.of(), next);
  }

  private void rejectCheckpointWrites() throws SQLException {
    execute(
        "CREATE TRIGGER reject_checkpoint BEFORE INSERT ON checkpoints"
            + " BEGIN SELECT RAISE(ABORT, 'synthetic checkpoint failure'); END");
  }

  private void execute(String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Override
  public void close() {
    store.close();
  }
}
