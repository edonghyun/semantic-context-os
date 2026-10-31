package io.scos.client;

import io.scos.client.source.transcript.TranscriptLines;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 합성 프로젝트와 SQLite 설치 파일의 형식·경로·변경을 소유한다. */
final class LocalInstallation {
  private final ClientPaths paths;
  private final Clock clock;

  LocalInstallation(Path root, Clock clock) {
    this.clock = clock;
    paths =
        ClientPaths.from(
            Map.of(
                "SCOS_HOME",
                root.resolve("scos").toString(),
                "SCOS_CLAUDE_PROJECTS",
                root.resolve("claude-projects").toString(),
                "SCOS_QUERY_LOG",
                ""));
  }

  ClientPaths paths() {
    return paths;
  }

  void givenSession() throws IOException {
    Files.createDirectories(sessionFile().getParent());
    try (var input = getClass().getResourceAsStream("/transcripts/turns.jsonl")) {
      Files.write(sessionFile(), input.readAllBytes());
    }
  }

  void givenMemory() throws IOException {
    writeMemory(
        "---\nname: 고양이 메모\ndescription: 합성 검색 자료\ntype: user\n---\n# 고양이 메모\n\n보라색 고양이는 사용자가 좋아하는 합성 예시이며 검색 확인용으로만 쓴다.\n");
    Files.setLastModifiedTime(memoryFile(), FileTime.from(clock.instant()));
  }

  void givenProject() throws IOException {
    givenSession();
    givenMemory();
  }

  void givenSmallProjectWithRejectedLine() throws IOException {
    Files.createDirectories(sessionFile().getParent());
    Files.writeString(
        sessionFile(),
        TranscriptLines.user("첫 질문").build()
            + "\n"
            + "{broken\n"
            + TranscriptLines.user("다음 질문").build()
            + "\n");
    givenMemory();
  }

  void appendUser(String text) throws IOException {
    Files.writeString(
        sessionFile(),
        TranscriptLines.user(text).timestamp("2026-10-27T10:05:00.000Z").cwd("/w/p").build() + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
  }

  void writeMemory(String text) throws IOException {
    Files.createDirectories(memoryFile().getParent());
    Files.writeString(memoryFile(), text);
  }

  void deleteSession() throws IOException {
    Files.delete(sessionFile());
  }

  void deleteMemory() throws IOException {
    Files.delete(memoryFile());
  }

  void givenMissingProjectsRoot() throws IOException {
    try (Stream<Path> files = Files.walk(paths.claudeProjects())) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
    }
  }

  private Path sessionFile() {
    return paths.claudeProjects().resolve("d/s1.jsonl");
  }

  private Path memoryFile() {
    return paths.claudeProjects().resolve("d/memory/a.md");
  }

  void deleteIndex() throws IOException {
    for (String suffix : List.of("", "-wal", "-shm")) {
      Files.deleteIfExists(Path.of(paths.index() + suffix));
    }
  }

  String stateBytes() throws IOException {
    return allBytes(paths.state());
  }

  String indexBytes() throws IOException {
    return allBytes(paths.index());
  }

  private String allBytes(Path file) throws IOException {
    StringBuilder all =
        new StringBuilder(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    Path wal = Path.of(file + "-wal");
    if (Files.exists(wal)) all.append(new String(Files.readAllBytes(wal), StandardCharsets.UTF_8));
    return all.toString();
  }

  void givenMemoryModifiedBeforeCollection() throws IOException {
    Files.setLastModifiedTime(
        paths.claudeProjects().resolve("d/memory/a.md"),
        FileTime.from(Instant.parse("2026-09-01T03:04:05Z")));
  }

  void givenVersionOneState() throws SQLException {
    execute(
        paths.state(),
        "PRAGMA user_version = 1",
        "ALTER TABLE raw_items DROP COLUMN media_type",
        "UPDATE raw_items SET source_updated_at = NULL WHERE source = 'claude-memory'");
    execute(
        paths.index(),
        "UPDATE units SET captured_at = '2026-10-28T00:00:00Z' WHERE document_id LIKE 'memory/%'",
        "UPDATE units SET captured_at = '' WHERE document_id LIKE 'transcript/%'");
  }

  private void execute(Path file, String... sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      for (String command : sql) statement.execute(command);
    }
  }
}
