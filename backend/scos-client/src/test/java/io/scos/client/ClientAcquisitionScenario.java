package io.scos.client;

import io.scos.client.collect.CollectionReport;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 운영 조립에서 state 열기 실패를 일으키고 같은 조립의 재획득을 관찰한다. */
final class ClientAcquisitionScenario implements AutoCloseable {
  private final ScosLocalScenario local;

  ClientAcquisitionScenario(Path root) throws IOException, SQLException {
    local = new ScosLocalScenario(root);
    local.givenProject();
    local.assemblyIds("준비");
    setVersion(999);
  }

  CollectionReport collect() {
    return local.collectWithLock();
  }

  void givenFailedStateOpen() {
    try {
      collect();
    } catch (IllegalStateException expected) {
      if (expected.getMessage().equals("state.sqlite schema 999 is newer than 3")) return;
      throw expected;
    }
    throw new IllegalStateException("given 준비 실패: 새 스키마 열기 거부");
  }

  void repairState() throws SQLException {
    setVersion(0);
  }

  boolean lockAvailable() {
    return local.lockAvailable();
  }

  private void setVersion(int version) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + local.state());
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = " + version);
    }
  }

  @Override
  public void close() {
    local.close();
  }
}
