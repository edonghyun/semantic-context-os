package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.fetch.FetchLog;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.source.SourceInstanceId;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * A separate SQLite archive. Each content write is one transaction, including comments and moves.
 */
public final class ArchiveStore implements AutoCloseable, SourceFreshness {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ArchiveDatabase db;

  private ArchiveStore(Path file) {
    db = new ArchiveDatabase(file);
  }

  public static ArchiveStore open(Path file) {
    return new ArchiveStore(file);
  }

  public synchronized void upsertBoards(String site, List<Board> boards, Instant seenAt) {
    db.transaction(
        () -> {
          db.execute("INSERT OR IGNORE INTO site(id) VALUES(?)", site);
          db.execute("UPDATE board SET active=0 WHERE site=?", site);
          for (Board b : boards)
            db.execute(
                """
    INSERT INTO board(site,id,name,category,seen_at,active) VALUES(?,?,?,?,?,1)
    ON CONFLICT(site,id) DO UPDATE SET name=excluded.name,category=excluded.category,seen_at=excluded.seen_at,active=1
    """,
                site,
                b.id(),
                b.name(),
                b.category(),
                seenAt.toString());
          return true;
        });
  }

  public synchronized Optional<PostState> post(String site, String postId) {
    return db.query("SELECT * FROM post WHERE site=? AND id=?", this::state, site, postId).stream()
        .findFirst();
  }

  private PostState state(ResultSet r) throws SQLException {
    Optional<Instant> updated =
        Optional.ofNullable(r.getString("signal_updated")).map(Instant::parse);
    Optional<String> count = Optional.ofNullable(r.getString("signal_comments"));
    Optional<ChangeSignal> signal =
        r.getInt("has_signal") == 0
            ? Optional.empty()
            : Optional.of(
                new ChangeSignal(
                    updated,
                    count.isPresent()
                        ? OptionalInt.of(Integer.parseInt(count.get()))
                        : OptionalInt.empty()));
    return new PostState(
        r.getString("site"),
        r.getString("id"),
        r.getString("board_id"),
        PostStatus.valueOf(r.getString("status")),
        signal,
        Optional.ofNullable(r.getString("fetched_at")).map(Instant::parse),
        Optional.ofNullable(r.getString("projected_hash")));
  }

  /** The URL the latest archived revision was read from. */
  public synchronized Optional<String> archivedUrl(String site, String postId) {
    return db
        .query(
            "SELECT url FROM post_revision WHERE site=? AND post_id=? ORDER BY id DESC LIMIT 1",
            r -> r.getString(1),
            site,
            postId)
        .stream()
        .findFirst();
  }

  public synchronized void recordSummary(
      String site, String boardId, PostSummary s, Instant seenAt) {
    db.transaction(
        () -> {
          ensurePost(site, boardId, s.postId(), s.createdAt(), seenAt);
          move(site, boardId, s.postId(), seenAt);
          PostState old = post(site, s.postId()).orElseThrow();
          if (!old.lastSignal().equals(s.signal()) || firstTerminalReappearance(site, s.postId()))
            db.execute("UPDATE post SET pending_fetch=1 WHERE site=? AND id=?", site, s.postId());
          db.execute(
              """
    UPDATE post SET created_at=?,has_signal=?,signal_updated=?,signal_comments=?,last_seen_at=?,
    status=CASE WHEN status='NEEDS_CHECK' THEN 'NORMAL' ELSE status END,gone_sweep=NULL,gone_at=NULL WHERE site=? AND id=?
    """,
              s.createdAt().toString(),
              s.signal().isPresent() ? 1 : 0,
              s.signal().flatMap(ChangeSignal::updatedAt).map(Instant::toString),
              s.signal()
                  .filter(v -> v.commentCount().isPresent())
                  .map(v -> v.commentCount().getAsInt()),
              seenAt.toString(),
              site,
              s.postId());
          return true;
        });
  }

  /** A missing terminal post may reappear once without changing its signal. */
  private boolean firstTerminalReappearance(String site, String id) {
    return db.execute(
            "UPDATE post SET terminal_seen=1 WHERE site=? AND id=? AND status IN ('DELETED','ACCESS_DENIED') AND terminal_seen=0",
            site,
            id)
        > 0;
  }

  private void ensurePost(String site, String board, String id, Instant created, Instant seen) {
    db.execute(
        "INSERT OR IGNORE INTO post(site,id,board_id,status,created_at,first_seen_at,last_seen_at) VALUES(?,?,?,'NORMAL',?,?,?)",
        site,
        id,
        board,
        created.toString(),
        seen.toString(),
        seen.toString());
  }

  private void move(String site, String board, String id, Instant at) {
    PostState old = post(site, id).orElseThrow();
    if (old.boardId().equals(board)) return;
    db.execute(
        "INSERT INTO post_board_history(site,post_id,from_board,to_board,moved_at) VALUES(?,?,?,?,?)",
        site,
        id,
        old.boardId(),
        board,
        at.toString());
    db.execute("UPDATE post SET board_id=? WHERE site=? AND id=?", board, site, id);
  }

  public synchronized boolean storeFound(
      String site, String boardId, String postId, FetchResult.Found f, Instant fetchedAt) {
    return db.transaction(
        () -> {
          ensurePost(site, boardId, postId, f.createdAt(), fetchedAt);
          move(site, boardId, postId, fetchedAt);
          boolean changed = storePost(site, postId, f, fetchedAt);
          for (CommentData c : f.comments())
            changed = storeComment(site, postId, c, fetchedAt) || changed;
          if (f.commentsComplete())
            changed = deleteMissingComments(site, postId, f.comments()) || changed;
          db.execute(
              "UPDATE post SET status='NORMAL',pending_fetch=0,terminal_seen=0,gone_sweep=NULL,gone_at=NULL,fetched_at=?,created_at=? WHERE site=? AND id=?",
              fetchedAt.toString(),
              f.createdAt().toString(),
              site,
              postId);
          if (changed)
            db.execute(
                "UPDATE post SET content_at=? WHERE site=? AND id=?",
                fetchedAt.toString(),
                site,
                postId);
          return changed;
        });
  }

  private boolean storePost(String site, String id, FetchResult.Found f, Instant at) {
    String hash = ContentHash.post(f);
    var previous =
        db.query(
            "SELECT hash FROM post_revision WHERE site=? AND post_id=? ORDER BY id DESC LIMIT 1",
            r -> r.getString(1),
            site,
            id);
    if (previous.contains(hash)) return false;
    db.execute(
        """
   INSERT INTO post_revision(site,post_id,hash,url,raw,title,text,author,created_at,attachments,fetched_at)
   VALUES(?,?,?,?,?,?,?,?,?,?,?)
   """,
        site,
        id,
        hash,
        f.url(),
        f.raw(),
        f.title(),
        f.text(),
        f.author(),
        f.createdAt().toString(),
        JSON.writeValueAsString(f.attachments()),
        at.toString());
    return true;
  }

  private boolean storeComment(String site, String postId, CommentData c, Instant at) {
    String hash = ContentHash.comment(c);
    var old =
        db.query(
            "SELECT hash || ':' || status FROM comment WHERE site=? AND post_id=? AND id=?",
            r -> r.getString(1),
            site,
            postId,
            c.id());
    boolean changed = !old.contains(hash + ":NORMAL");
    if (!changed) return false;
    db.execute(
        """
   INSERT INTO comment(site,post_id,id,status,hash,parent_id) VALUES(?,?,?,'NORMAL',?,?)
   ON CONFLICT(site,post_id,id) DO UPDATE SET status='NORMAL',hash=excluded.hash,parent_id=excluded.parent_id
   """,
        site,
        postId,
        c.id(),
        hash,
        c.parentId());
    if (old.isEmpty() || !old.getFirst().startsWith(hash + ":"))
      db.execute(
          """
   INSERT INTO comment_revision(site,post_id,comment_id,hash,text,author,created_at,parent_id,fetched_at)
   VALUES(?,?,?,?,?,?,?,?,?)
   """,
          site,
          postId,
          c.id(),
          hash,
          c.text(),
          c.author(),
          c.createdAt().toString(),
          c.parentId(),
          at.toString());
    return true;
  }

  private boolean deleteMissingComments(String site, String post, List<CommentData> comments) {
    Set<String> visible = new HashSet<>();
    comments.forEach(c -> visible.add(c.id()));
    var current =
        db.query(
            "SELECT id FROM comment WHERE site=? AND post_id=? AND status='NORMAL'",
            r -> r.getString(1),
            site,
            post);
    boolean changed = false;
    for (String id : current)
      if (!visible.contains(id)) {
        db.execute(
            "UPDATE comment SET status='DELETED' WHERE site=? AND post_id=? AND id=?",
            site,
            post,
            id);
        changed = true;
      }
    return changed;
  }

  public synchronized void markStatus(String site, String postId, PostStatus status) {
    db.execute(
        "UPDATE post SET status=?,terminal_seen=0,pending_fetch=CASE WHEN ? IN ('DELETED','ACCESS_DENIED') THEN 0 ELSE pending_fetch END WHERE site=? AND id=?",
        status.name(),
        status.name(),
        site,
        postId);
  }

  /**
   * Records that this sweep found the archived post gone, unless timed gone evidence already
   * exists. Evidence without a time, left by the V5 schema, restarts at this sweep. Returns when an
   * earlier sweep first found it gone, with no sighting since.
   */
  public synchronized Optional<Instant> recordGone(
      String site, String postId, long sweepId, Instant at) {
    return db.transaction(
        () -> {
          db.execute(
              "UPDATE post SET gone_sweep=?,gone_at=? WHERE site=? AND id=? AND gone_at IS NULL",
              sweepId,
              at.toString(),
              site,
              postId);
          return db
              .query(
                  "SELECT gone_at FROM post WHERE site=? AND id=? AND gone_sweep<>?",
                  r -> Instant.parse(r.getString(1)),
                  site,
                  postId,
                  sweepId)
              .stream()
              .findFirst();
        });
  }

  /** A body verdict for a listed post consumes its reappearance and starts the retry interval. */
  public synchronized void recordFetchStatus(
      String site, String postId, PostStatus status, Instant at) {
    db.execute(
        "UPDATE post SET status=?,pending_fetch=0,terminal_seen=1,fetched_at=? WHERE site=? AND id=?",
        status.name(),
        at.toString(),
        site,
        postId);
  }

  public synchronized BoardCursors cursors(String site, String boardId) {
    return db
        .query(
            "SELECT * FROM board WHERE site=? AND id=?",
            r -> {
              Optional<PostKey> front = key(r, "front_time", "front_id");
              Optional<BackfillCursor> back =
                  key(r, "back_time", "back_id")
                      .map(k -> new BackfillCursor(uncheckedString(r, "back_cursor"), k));
              return new BoardCursors(front, back, r.getInt("backfill_done") != 0);
            },
            site,
            boardId)
        .stream()
        .findFirst()
        .orElse(new BoardCursors(Optional.empty(), Optional.empty(), false));
  }

  private static String uncheckedString(ResultSet rs, String col) {
    try {
      return Objects.requireNonNull(rs.getString(col));
    } catch (SQLException e) {
      throw new IllegalStateException("Invalid cursor", e);
    }
  }

  private static Optional<PostKey> key(ResultSet r, String time, String id) throws SQLException {
    String t = r.getString(time);
    return t == null
        ? Optional.empty()
        : Optional.of(new PostKey(Instant.parse(t), Objects.requireNonNull(r.getString(id))));
  }

  public synchronized void saveCursors(String site, String boardId, BoardCursors c) {
    db.execute(
        "UPDATE board SET front_time=?,front_id=?,back_time=?,back_id=?,back_cursor=?,backfill_done=? WHERE site=? AND id=?",
        c.front().map(k -> k.createdAt().toString()),
        c.front().map(PostKey::postId),
        c.back().map(b -> b.key().createdAt().toString()),
        c.back().map(b -> b.key().postId()),
        c.back().map(BackfillCursor::driverCursor),
        c.backfillDone() ? 1 : 0,
        site,
        boardId);
  }

  public synchronized List<PostState> postsCreatedSince(
      String site, String boardId, Instant since) {
    return db.query(
        "SELECT * FROM post WHERE site=? AND board_id=? AND created_at>=? ORDER BY created_at DESC,id DESC",
        this::state,
        site,
        boardId,
        since.toString());
  }

  /** Deferred bodies and observed changes remain discoverable after their list cursor advances. */
  public synchronized List<PostState> postsAwaitingFetch(
      String site, String boardId, Instant since) {
    return db.query(
        """
        SELECT * FROM post WHERE site=? AND board_id=? AND (
          (fetched_at IS NULL AND status IN ('NORMAL','NEEDS_CHECK')) OR
          (pending_fetch=1 AND (status IN ('DELETED','ACCESS_DENIED') OR created_at>=?)))
        ORDER BY created_at DESC,id DESC
        """,
        this::state,
        site,
        boardId,
        since.toString());
  }

  public synchronized long startRun(String site, Instant at) {
    db.execute("INSERT OR IGNORE INTO site(id) VALUES(?)", site);
    db.execute("INSERT INTO crawl_run(site,started_at) VALUES(?,?)", site, at.toString());
    return db.insertedId();
  }

  public synchronized void finishRun(
      long runId,
      RunStats stats,
      FetchLog log,
      Optional<String> failureReason,
      Instant finishedAt) {
    db.execute(
        "UPDATE crawl_run SET finished=1,finished_at=?,stats=?,requests=?,by_status=?,permission=?,failure_reason=? WHERE id=? AND finished=0",
        finishedAt.toString(),
        JSON.writeValueAsString(stats),
        log.requests(),
        JSON.writeValueAsString(log.byStatus()),
        log.robotsExceptionUsed().map(JSON::writeValueAsString),
        failureReason,
        runId);
  }

  /** The site id is the source instance; a run without a failure reason is a success. */
  @Override
  public synchronized Map<SourceInstanceId, Instant> lastSucceeded(
      Set<SourceInstanceId> instances) {
    Map<SourceInstanceId, Instant> last = new HashMap<>();
    for (SourceInstanceId instance : instances)
      db
          .query(
              "SELECT finished_at FROM crawl_run WHERE site=? AND finished=1 AND failure_reason IS NULL",
              r -> Instant.parse(r.getString(1)),
              instance.value())
          .stream()
          .max(Instant::compareTo)
          .ifPresent(at -> last.put(instance, at));
    return Map.copyOf(last);
  }

  public synchronized int completedRuns(String site) {
    return db.query(
            "SELECT count(*) FROM crawl_run WHERE site=? AND finished=1 AND failure_reason IS NULL",
            r -> r.getInt(1),
            site)
        .getFirst();
  }

  public synchronized Optional<SweepState> openSweep(String site) {
    return db
        .query(
            "SELECT id,started_at FROM sweep WHERE site=? AND closed=0",
            r -> sweepState(r.getLong(1), Instant.parse(r.getString(2))),
            site)
        .stream()
        .findFirst();
  }

  private SweepState sweepState(long id, Instant at) {
    Map<String, Optional<String>> cursors = new LinkedHashMap<>();
    Set<String> complete = new HashSet<>();
    db.query(
        "SELECT board_id,cursor,complete FROM sweep_board WHERE sweep_id=? ORDER BY board_id",
        r -> {
          String board = r.getString(1);
          cursors.put(board, Optional.ofNullable(r.getString(2)));
          if (r.getInt(3) != 0) complete.add(board);
          return board;
        },
        id);
    return new SweepState(id, at, cursors, complete);
  }

  public synchronized SweepState startSweep(String site, Instant at) {
    return db.transaction(
        () -> {
          db.execute("INSERT INTO sweep(site,started_at) VALUES(?,?)", site, at.toString());
          long id = db.insertedId();
          db.execute(
              "INSERT INTO sweep_board(sweep_id,board_id) SELECT ?,id FROM board WHERE site=? AND active=1",
              id,
              site);
          return sweepState(id, at);
        });
  }

  public synchronized void recordSweepPage(
      long sweepId, String boardId, List<String> postIds, Optional<String> nextCursor) {
    db.transaction(
        () -> {
          for (String id : postIds)
            db.execute(
                "INSERT OR IGNORE INTO sweep_seen(sweep_id,post_id) VALUES(?,?)", sweepId, id);
          db.execute(
              "UPDATE sweep_board SET cursor=? WHERE sweep_id=? AND board_id=?",
              nextCursor,
              sweepId,
              boardId);
          return true;
        });
  }

  public synchronized void markBoardListed(long sweepId, String boardId, boolean complete) {
    db.execute(
        "UPDATE sweep_board SET complete=? WHERE sweep_id=? AND board_id=?",
        complete ? 1 : 0,
        sweepId,
        boardId);
  }

  public synchronized Set<String> missingAfterSweep(long sweepId) {
    String site =
        db
            .query("SELECT site FROM sweep WHERE id=? AND closed=0", r -> r.getString(1), sweepId)
            .stream()
            .findFirst()
            .orElseThrow();
    SweepState state = openSweep(site).orElseThrow();
    Set<String> boards =
        new HashSet<>(
            db.query("SELECT id FROM board WHERE site=? AND active=1", r -> r.getString(1), site));
    if (!boards.equals(state.boardCursors().keySet()) || !boards.equals(state.completeBoards()))
      throw new IllegalStateException("Incomplete sweep cannot be compared");
    return new HashSet<>(
        db
            .query(
                """
   SELECT p.id,p.first_seen_at FROM post p WHERE p.site=? AND p.status IN ('NORMAL','NEEDS_CHECK')
   AND NOT EXISTS(SELECT 1 FROM sweep_seen s WHERE s.sweep_id=? AND s.post_id=p.id)
   AND NOT EXISTS(SELECT 1 FROM sweep_checked c WHERE c.sweep_id=? AND c.post_id=p.id)
   """,
                r -> Map.entry(r.getString(1), Instant.parse(r.getString(2))),
                site,
                sweepId,
                sweepId)
            .stream()
            .filter(e -> !e.getValue().isAfter(state.startedAt()))
            .map(Map.Entry::getKey)
            .toList());
  }

  public synchronized void closeSweep(long sweepId, boolean compared) {
    db.execute("UPDATE sweep SET closed=1,compared=? WHERE id=?", compared ? 1 : 0, sweepId);
  }

  public synchronized void discardSweep(long sweepId, String reason) {
    db.execute(
        "UPDATE sweep SET closed=1,compared=0,discard_reason=? WHERE id=? AND closed=0",
        reason,
        sweepId);
  }

  public synchronized List<ProjectionRow> projectionRows(String site, Set<String> boardIds) {
    return db
        .query(
            """
   SELECT p.id,p.board_id,p.projected_hash,b.name,r.url,r.title,r.text,r.created_at
   FROM post p JOIN board b ON b.site=p.site AND b.id=p.board_id
   JOIN post_revision r ON r.id=(SELECT max(id) FROM post_revision WHERE site=p.site AND post_id=p.id)
   WHERE p.site=? AND p.status IN ('NORMAL','NEEDS_CHECK') ORDER BY p.id
   """,
            r -> Map.entry(r.getString("board_id"), projectionRow(site, r)),
            site)
        .stream()
        .filter(e -> boardIds.contains(e.getKey()))
        .map(Map.Entry::getValue)
        .toList();
  }

  private ProjectionRow projectionRow(String site, ResultSet r) throws SQLException {
    String id = r.getString("id");
    List<CommentData> comments =
        db.query(
            """
   SELECT c.id,c.parent_id,r.author,r.created_at,r.text FROM comment c JOIN comment_revision r
   ON r.id=(SELECT max(id) FROM comment_revision WHERE site=c.site AND post_id=c.post_id AND comment_id=c.id)
   WHERE c.site=? AND c.post_id=? AND c.status='NORMAL' ORDER BY r.created_at,c.id
   """,
            row ->
                new CommentData(
                    row.getString(1),
                    Optional.ofNullable(row.getString(2)),
                    row.getString(3),
                    Instant.parse(row.getString(4)),
                    row.getString(5)),
            site,
            id);
    comments =
        comments.stream()
            .sorted(Comparator.comparing(CommentData::createdAt).thenComparing(CommentData::id))
            .toList();
    return new ProjectionRow(
        id,
        r.getString("name"),
        Optional.ofNullable(r.getString("projected_hash")),
        r.getString("url"),
        r.getString("title"),
        r.getString("text"),
        Instant.parse(r.getString("created_at")),
        comments);
  }

  public synchronized void setProjectedHash(String site, String postId, Optional<String> hash) {
    db.execute("UPDATE post SET projected_hash=? WHERE site=? AND id=?", hash, site, postId);
  }

  /** Clear excluded hashes only after the caller has accepted the live-set safety check. */
  public synchronized void retainProjectedHashes(String site, Set<String> livePostIds) {
    db.transaction(
        () -> {
          var projected =
              db.query(
                  "SELECT id FROM post WHERE site=? AND projected_hash IS NOT NULL",
                  r -> r.getString(1),
                  site);
          for (String id : projected)
            if (!livePostIds.contains(id)) setProjectedHash(site, id, Optional.empty());
          return true;
        });
  }

  public synchronized OptionalInt lastLiveCount(String site) {
    return db
        .query(
            "SELECT live_count FROM site WHERE id=? AND live_count IS NOT NULL",
            r -> r.getInt(1),
            site)
        .stream()
        .mapToInt(Integer::intValue)
        .findFirst();
  }

  public synchronized void saveLiveCount(String site, int n) {
    db.execute(
        "INSERT INTO site(id,live_count) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET live_count=excluded.live_count",
        site,
        n);
  }

  public synchronized boolean needsFetch(String site, String id) {
    return db
        .query(
            "SELECT pending_fetch FROM post WHERE site=? AND id=?", r -> r.getInt(1) != 0, site, id)
        .stream()
        .findFirst()
        .orElse(false);
  }

  public synchronized boolean hasUnlistedBoards(String site, Set<String> listed) {
    return db
        .query(
            "SELECT DISTINCT board_id FROM post WHERE site=? AND status IN ('NORMAL','NEEDS_CHECK')",
            r -> r.getString(1),
            site)
        .stream()
        .anyMatch(id -> !listed.contains(id));
  }

  public synchronized void markSweepChecked(long sweepId, String postId) {
    db.execute(
        "INSERT OR IGNORE INTO sweep_checked(sweep_id,post_id) VALUES(?,?)", sweepId, postId);
  }

  public synchronized Optional<CrawlCheckpoint> crawlCheckpoint(String site, String board) {
    return db
        .query(
            "SELECT checkpoint FROM board WHERE site=? AND id=? AND checkpoint IS NOT NULL",
            r -> JSON.readValue(r.getString(1), CrawlCheckpoint.class),
            site,
            board)
        .stream()
        .findFirst();
  }

  public synchronized void saveCrawlCheckpoint(
      String site, String board, Optional<CrawlCheckpoint> checkpoint) {
    db.execute(
        "UPDATE board SET checkpoint=? WHERE site=? AND id=?",
        checkpoint.map(JSON::writeValueAsString),
        site,
        board);
  }

  @Override
  public synchronized void close() {
    db.close();
  }
}
