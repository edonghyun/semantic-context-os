package io.scos.boardarchive.testsupport;

import io.scos.boardarchive.crawl.*;
import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.fetch.*;
import io.scos.boardarchive.store.*;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class ArchiveScenario implements AutoCloseable {
  public static final String SITE = "example-board";
  public static final Instant CREATED = Instant.parse("2026-11-17T00:00:00Z");
  // Undoes migrations V2 to V6, one column each, in version order.
  private static final List<String> UNDO_FROM_V2 =
      List.of(
          "ALTER TABLE sweep DROP COLUMN discard_reason",
          "ALTER TABLE post DROP COLUMN terminal_seen",
          "ALTER TABLE crawl_run DROP COLUMN failure_reason",
          "ALTER TABLE post DROP COLUMN gone_sweep",
          "ALTER TABLE post DROP COLUMN gone_at");
  public ArchiveStore store;
  private final Path file;
  public final MutableClock clock = new MutableClock();

  public ArchiveScenario(Path file) {
    this.file = file;
    store = ArchiveStore.open(file);
    store.upsertBoards(
        SITE, List.of(new Board("a", "General", ""), new Board("b", "Other", "")), clock.instant());
  }

  public FetchResult.Found found(
      String text, String raw, List<CommentData> comments, boolean complete) {
    return new FetchResult.Found(
        "urn:example:post:42",
        raw,
        "Title",
        text,
        "Writer",
        CREATED,
        List.of("urn:example:attachment:1"),
        comments,
        complete);
  }

  public FetchResult.Found found(String text) {
    return found(text, "<article>" + text + "</article>", List.of(), true);
  }

  public CommentData comment(String text) {
    return new CommentData("c1", Optional.empty(), "Reader", CREATED.plusSeconds(60), text);
  }

  public boolean save(FetchResult.Found found) {
    return store.storeFound(SITE, "a", "42", found, clock.instant());
  }

  public void givenPost() {
    save(found("Body"));
  }

  public void recordSummaryInBoard(String board) {
    store.recordSummary(
        SITE,
        board,
        new PostSummary("42", "Title", CREATED, Optional.empty(), false),
        clock.instant());
  }

  public record RunRecord(
      boolean finished,
      int requests,
      int newPosts,
      Optional<String> permissionEvidence,
      Optional<String> failureReason) {}

  public RunRecord lastRun() throws SQLException {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "select finished, requests, json_extract(stats, '$.newPosts'), "
                    + "json_extract(permission, '$.evidence'), failure_reason from crawl_run order by id desc limit 1")) {
      if (!rs.next()) throw new IllegalStateException("given 준비 실패: 실행 기록이 있어야 한다");
      return new RunRecord(
          rs.getBoolean(1),
          rs.getInt(2),
          rs.getInt(3),
          Optional.ofNullable(rs.getString(4)),
          Optional.ofNullable(rs.getString(5)));
    }
  }

  public List<String> postRevisionTexts() throws SQLException {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var st = c.createStatement();
        var rs = st.executeQuery("select text from post_revision order by id")) {
      List<String> texts = new ArrayList<>();
      while (rs.next()) texts.add(rs.getString(1));
      return List.copyOf(texts);
    }
  }

  public String commentStatus() throws SQLException {
    return sql("select status from comment");
  }

  public int boardMoveCount() throws SQLException {
    return Integer.parseInt(sql("select count(*) from post_board_history"));
  }

  public String journalMode() throws SQLException {
    return sql("PRAGMA journal_mode");
  }

  public String sweepDiscardReason() throws SQLException {
    return sql("select discard_reason from sweep order by id desc limit 1");
  }

  private String sql(String sql) throws SQLException {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : "";
    }
  }

  public Map<String, Integer> revisionCounts() throws SQLException {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var st = c.createStatement();
        var rs = st.executeQuery("select post_id, count(*) from post_revision group by post_id")) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      while (rs.next()) counts.put(rs.getString(1), rs.getInt(2));
      return Map.copyOf(counts);
    }
  }

  @Override
  public void close() {
    store.close();
  }

  public void givenLegacyCheckpoint() throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          UPDATE board SET checkpoint='{"phase":"RECHECK","driverCursor":"3","after":null,"boundary":null}'
          WHERE site='example-board' AND id='a'
          """);
    }
  }

  /** Rewinds the file to an earlier schema version, then reopens it to run the migrations. */
  public void reopenArchiveAt(int version) throws SQLException {
    store.close();
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var statement = connection.createStatement()) {
      for (String undo : UNDO_FROM_V2.subList(version - 1, UNDO_FROM_V2.size()))
        statement.execute(undo);
      statement.execute("PRAGMA user_version=" + version);
    }
    store = ArchiveStore.open(file);
  }

  /** Leaves gone evidence as a V5 archive wrote it: a sweep with no time, then migrates to V6. */
  public void givenVersionFiveGoneEvidence(long sweepId) throws SQLException {
    store.recordGone(SITE, "42", sweepId, clock.instant());
    reopenArchiveAt(5);
  }

  public void completeLoggedRun() throws Exception {
    long id = store.startRun(SITE, clock.instant());
    try (LocalSite site = new LocalSite();
        HttpClient client = HttpClient.newHttpClient()) {
      site.respond("/robots.txt", 200, "User-agent: *\nDisallow: /");
      site.respond("/post", 200, "body");
      var permission =
          new Permission("maintainer", LocalDate.of(2026, 11, 17), "/", "local permission record");
      var policy =
          new SitePolicy("archive-test", Duration.ofSeconds(3), 4, Optional.of(permission));
      var log = new FetchLog();
      new PoliteFetcher(client, clock, clock::advance, policy, log).get(site.uri("/post"));
      store.finishRun(id, new RunStats(2, 1, 0, List.of(), false), log);
    }
  }
}
