package io.scos.client;

import io.scos.client.collect.CollectionReport;
import io.scos.client.collect.IncrementalCollector;
import io.scos.support.LogCapture;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class ClientCollectionScenario implements AutoCloseable {
  private final ScosLocalScenario local;
  private final LogCapture logs = LogCapture.of(IncrementalCollector.class);

  ClientCollectionScenario(Path root) throws IOException {
    local = new ScosLocalScenario(root);
    local.givenSession();
    // 이 문맥은 수집 전 색인 스키마에도 장애를 주입한다.
    local.assemblyIds("합성 준비 질의");
  }

  void givenUnreadableCheckpoints() throws SQLException {
    local.collectWithLock();
    execute(local.state(), "DROP TABLE checkpoints");
  }

  void givenStateProjectionFailure(String phase) throws SQLException {
    local.collectWithLock();
    execute(
        local.state(),
        "INSERT INTO pending_projection VALUES ('claude-transcript', 'transcript/d/s1')");
    if (phase.equals("read")) {
      execute(local.state(), "DROP TABLE raw_items");
    } else {
      execute(
          local.state(),
          "CREATE TRIGGER reject_clear BEFORE DELETE ON pending_projection"
              + " BEGIN SELECT RAISE(ABORT, 'synthetic state failure'); END");
    }
  }

  void givenUnreadableIndexMetadata() throws SQLException {
    execute(local.index(), "DROP TABLE space_meta");
  }

  void givenRejectedDocumentWrites() throws SQLException {
    execute(
        local.index(),
        "CREATE TRIGGER reject_units BEFORE INSERT ON units"
            + " BEGIN SELECT RAISE(ABORT, 'synthetic private document body'); END");
  }

  CollectionReport collect() {
    return local.collectWithLock();
  }

  List<JsonNode> events(String name) {
    return logs.events(name);
  }

  private void execute(Path file, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Override
  public void close() {
    try {
      local.close();
    } finally {
      logs.close();
    }
  }
}
