package io.scos.boardarchive.crawl;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.fetch.*;
import io.scos.boardarchive.store.*;
import java.time.*;
import java.util.*;

/** Incremental crawl state is local to one run; archive cursors survive runner replacement. */
public final class CrawlRunner {
  private final ArchiveStore store;
  private final BoardDriver driver;
  private final Fetcher http;
  private final Clock clock;
  private final CrawlSettings settings;
  private final FetchLog requests;

  public CrawlRunner(
      ArchiveStore store,
      BoardDriver driver,
      Fetcher http,
      Clock clock,
      CrawlSettings settings,
      FetchLog requests) {
    this.store = store;
    this.driver = driver;
    this.http = http;
    this.clock = clock;
    this.settings = settings;
    this.requests = requests;
  }

  public RunStats run() {
    return new Run().execute();
  }

  private static final class BoardReadFailure extends RuntimeException {
    BoardReadFailure(RuntimeException cause) {
      super(cause);
    }
  }

  private final class Run {
    private final Instant started = clock.instant();
    private final Instant deadline = started.plus(settings.timeBudget());
    private final Set<String> attemptedPosts = new HashSet<>();
    private int newPosts;
    private int changedPosts;
    private int failedPosts;
    private boolean sweepCompared;
    private final List<String> stopped = new ArrayList<>();

    private boolean hasTime() {
      return clock.instant().isBefore(deadline);
    }

    private RunStats execute() {
      long id = store.startRun(driver.site(), started);
      FetchLog before = requests.snapshot();
      Optional<String> failure = Optional.empty();
      try {
        crawlBoards();
        if (hasTime()
            && (store.openSweep(driver.site()).isPresent()
                || (store.completedRuns(driver.site()) + 1) % settings.sweepEveryRuns() == 0)) {
          SweepPass sweep = new SweepPass(store, driver, http, clock);
          sweepCompared = sweep.run(deadline);
          failedPosts += sweep.failedPosts();
        }
        return stats();
      } catch (RuntimeException e) {
        failure = Optional.of(e.getClass().getSimpleName());
        throw e;
      } finally {
        store.finishRun(id, stats(), requests.since(before), failure, clock.instant());
      }
    }

    private RunStats stats() {
      return new RunStats(newPosts, changedPosts, failedPosts, stopped, sweepCompared);
    }

    private void crawlBoards() {
      List<Board> boards = driver.boards(http);
      store.upsertBoards(driver.site(), boards, clock.instant());
      List<BoardScan> scans = boards.stream().map(BoardScan::new).toList();
      crawl(scans, CrawlCheckpoint.Phase.FRONT);
      crawl(scans, CrawlCheckpoint.Phase.RECHECK);
      fetchPending(scans);
      crawl(scans, CrawlCheckpoint.Phase.PENDING);
      crawl(scans, CrawlCheckpoint.Phase.BACKFILL);
    }

    private void crawl(List<BoardScan> scans, CrawlCheckpoint.Phase phase) {
      for (BoardScan scan : scans) {
        if (!hasTime()) break;
        if (stopped.contains(scan.board.id())) continue;
        try {
          scan.crawl(phase);
        } catch (BoardReadFailure e) {
          stopped.add(scan.board.id());
        }
      }
    }

    private void fetchPending(List<BoardScan> scans) {
      for (BoardScan scan : scans) {
        if (stopped.contains(scan.board.id())) continue;
        for (PostState post :
            store.postsAwaitingFetch(
                driver.site(), scan.board.id(), started.minus(settings.recheckWindow()))) {
          if (!hasTime()) return;
          if (!attemptedPosts.contains(post.postId()))
            fetch(scan.board, post.postId(), post.lastFetchedAt().isEmpty());
        }
      }
    }

    private void fetch(Board board, String postId, boolean neverFetched) {
      attemptedPosts.add(postId);
      FetchResult result;
      try {
        result = driver.fetchPost(http, board, postId);
      } catch (RuntimeException e) {
        failedPosts++;
        return;
      }
      switch (result) {
        case FetchResult.Found f -> {
          boolean changed = store.storeFound(driver.site(), board.id(), postId, f, clock.instant());
          if (neverFetched) newPosts++;
          else if (changed) changedPosts++;
        }
        case FetchResult.Deleted ignored ->
            store.recordFetchStatus(driver.site(), postId, PostStatus.DELETED, clock.instant());
        case FetchResult.AccessDenied ignored ->
            store.recordFetchStatus(
                driver.site(), postId, PostStatus.ACCESS_DENIED, clock.instant());
        case FetchResult.Unknown ignored -> failedPosts++;
      }
    }

    private final class BoardScan {
      private final Board board;
      private final Map<Optional<String>, ListPage> pages = new HashMap<>();
      private Optional<PostKey> front;
      private Optional<BackfillCursor> back;
      private boolean done;
      private CrawlCheckpoint checkpoint;

      BoardScan(Board board) {
        this.board = board;
        BoardCursors saved = store.cursors(driver.site(), board.id());
        front = saved.front();
        back = saved.back();
        done = saved.backfillDone();
        checkpoint =
            store
                .crawlCheckpoint(driver.site(), board.id())
                .orElse(
                    new CrawlCheckpoint(
                        front.isEmpty()
                            ? CrawlCheckpoint.Phase.BACKFILL
                            : CrawlCheckpoint.Phase.FRONT,
                        "",
                        Optional.empty(),
                        front));
        checkpoint = checkpoint.prioritizeFront(front);
      }

      void crawl(CrawlCheckpoint.Phase phase) {
        try {
          if (phase == CrawlCheckpoint.Phase.PENDING && checkpoint.phase() == phase && hasTime())
            nextPhase();
          else while (hasTime() && checkpoint.phase() == phase) scan();
        } finally {
          store.saveCursors(driver.site(), board.id(), new BoardCursors(front, back, done));
          store.saveCrawlCheckpoint(
              driver.site(),
              board.id(),
              checkpoint.phase() == CrawlCheckpoint.Phase.DONE
                  ? Optional.empty()
                  : Optional.of(checkpoint));
        }
      }

      private void scan() {
        Optional<String> cursor = Optional.of(checkpoint.driverCursor()).filter(v -> !v.isEmpty());
        Optional<String> previous = cursor;
        Set<String> visited = new HashSet<>();
        while (hasTime()) {
          if (!visited.add(cursor.orElse("")))
            throw new BoardReadFailure(new IllegalStateException("Repeated list cursor"));
          checkpoint =
              new CrawlCheckpoint(
                  checkpoint.phase(),
                  previous.orElse(""),
                  checkpoint.after(),
                  checkpoint.boundary(),
                  checkpoint.continuation());
          ListPage page;
          try {
            page = pages.computeIfAbsent(cursor, c -> driver.listPosts(http, board, c));
          } catch (RuntimeException e) {
            throw new BoardReadFailure(e);
          }
          boolean stopAfterPage = false;
          for (PostSummary summary : page.posts()) {
            // Approximate timestamps can reverse within a page, including a resumed overlap.
            stopAfterPage |= stopAt(summary);
            visit(summary);
            advance(summary, previous);
          }
          if (stopAfterPage || page.nextCursor().isEmpty()) {
            nextPhase();
            return;
          }
          previous = cursor;
          cursor = page.nextCursor();
        }
      }

      private boolean stopAt(PostSummary summary) {
        if (summary.pinned()) return false;
        return switch (checkpoint.phase()) {
          case FRONT ->
              checkpoint
                  .boundary()
                  .map(k -> summary.createdAt().isBefore(k.createdAt()))
                  .orElseGet(() -> beyondInitialDepth(summary));
          case RECHECK -> olderThan(summary, settings.recheckWindow());
          case BACKFILL -> beyondInitialDepth(summary);
          case PENDING, DONE -> false;
        };
      }

      private boolean beyondInitialDepth(PostSummary summary) {
        return settings.initialDepth().map(d -> olderThan(summary, d)).orElse(false);
      }

      private boolean olderThan(PostSummary summary, Duration age) {
        return summary.createdAt().isBefore(started.minus(age));
      }

      private void nextPhase() {
        if (checkpoint.phase() == CrawlCheckpoint.Phase.BACKFILL
            || (checkpoint.phase() == CrawlCheckpoint.Phase.FRONT
                && checkpoint.boundary().isEmpty())) done = true;
        checkpoint = checkpoint.nextPhase(new BoardCursors(front, back, done));
      }

      private void visit(PostSummary summary) {
        Optional<PostState> old = store.post(driver.site(), summary.postId());
        boolean never = old.flatMap(PostState::lastFetchedAt).isEmpty();
        store.recordSummary(driver.site(), board.id(), summary, clock.instant());
        boolean shouldFetch =
            switch (checkpoint.phase()) {
              case FRONT ->
                  old.isEmpty()
                      || old.filter(p -> p.lastFetchedAt().isPresent())
                          .map(p -> recheck(summary, p))
                          .orElse(false);
              case RECHECK -> never || old.map(p -> recheck(summary, p)).orElse(false);
              case BACKFILL -> never;
              case PENDING, DONE -> false;
            };
        if (shouldFetch && hasTime() && !attemptedPosts.contains(summary.postId()))
          fetch(board, summary.postId(), never);
      }

      private boolean recheck(PostSummary summary, PostState old) {
        if (olderThan(summary, settings.recheckWindow())) return false;
        if (store.needsFetch(driver.site(), summary.postId())) return true;
        if (summary.signal().isPresent()) return !summary.signal().equals(old.lastSignal());
        return old.lastFetchedAt()
            .map(at -> !at.plus(settings.noSignalRecheckEvery()).isAfter(started))
            .orElse(true);
      }

      private void advance(PostSummary summary, Optional<String> previous) {
        if (summary.pinned()) return;
        if (checkpoint.phase() != CrawlCheckpoint.Phase.RECHECK
            && (front.isEmpty() || summary.key().compareTo(front.get()) > 0))
          front = Optional.of(summary.key());
        if (checkpoint.phase() != CrawlCheckpoint.Phase.RECHECK
            && !done
            && (back.isEmpty() || summary.key().compareTo(back.get().key()) < 0))
          back = Optional.of(new BackfillCursor(previous.orElse(""), summary.key()));
        checkpoint =
            new CrawlCheckpoint(
                checkpoint.phase(),
                previous.orElse(""),
                Optional.of(summary.key()),
                checkpoint.boundary(),
                checkpoint.continuation());
      }
    }
  }
}
