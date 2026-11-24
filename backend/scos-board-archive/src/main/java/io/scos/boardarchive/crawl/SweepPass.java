package io.scos.boardarchive.crawl;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.store.*;
import java.time.*;
import java.util.*;

/** A sweep compares site-wide identities only after a complete unchanged board snapshot. */
public final class SweepPass {
  private static final Duration GONE_CONFIRMATION_GAP = Duration.ofHours(24);
  private final ArchiveStore store;
  private final BoardDriver driver;
  private final Fetcher http;
  private final Clock clock;
  private int failedPosts;

  public SweepPass(ArchiveStore store, BoardDriver driver, Fetcher http, Clock clock) {
    this.store = store;
    this.driver = driver;
    this.http = http;
    this.clock = clock;
  }

  public int failedPosts() {
    return failedPosts;
  }

  public boolean run(Instant deadline) {
    failedPosts = 0;
    List<Board> boards = driver.boards(http);
    store.upsertBoards(driver.site(), boards, clock.instant());
    SweepState state =
        store
            .openSweep(driver.site())
            .orElseGet(() -> store.startSweep(driver.site(), clock.instant()));
    Set<String> ids = new HashSet<>();
    boards.forEach(b -> ids.add(b.id()));
    if (!ids.equals(state.boardCursors().keySet()) || store.hasUnlistedBoards(driver.site(), ids)) {
      store.discardSweep(state.id(), "BOARD_SET_CHANGED");
      return false;
    }
    for (Board board : boards) {
      if (!state.completeBoards().contains(board.id()) && !listBoard(state, board, deadline))
        return false;
    }
    if (!clock.instant().isBefore(deadline)) return false;
    return confirm(state, boards, deadline);
  }

  private boolean listBoard(SweepState state, Board board, Instant deadline) {
    Optional<String> cursor = Objects.requireNonNull(state.boardCursors().get(board.id()));
    Set<String> visited = new HashSet<>();
    while (clock.instant().isBefore(deadline)) {
      if (!visited.add(cursor.orElse(""))) {
        store.discardSweep(state.id(), "REPEATED_CURSOR:" + board.id());
        return false;
      }
      ListPage page;
      try {
        page = driver.listPosts(http, board, cursor);
      } catch (RuntimeException e) {
        String reason =
            e instanceof HttpStatusException status
                ? "LIST_HTTP_" + status.status()
                : "LIST_" + e.getClass().getSimpleName();
        store.discardSweep(state.id(), reason + ":" + board.id());
        return false;
      }
      for (PostSummary p : page.posts())
        store.recordSummary(driver.site(), board.id(), p, clock.instant());
      store.recordSweepPage(
          state.id(),
          board.id(),
          page.posts().stream().map(PostSummary::postId).toList(),
          page.nextCursor());
      if (page.nextCursor().isEmpty()) {
        store.markBoardListed(state.id(), board.id(), true);
        return true;
      }
      cursor = page.nextCursor();
    }
    return false;
  }

  private boolean confirm(SweepState state, List<Board> boards, Instant deadline) {
    Map<String, Board> byId = new HashMap<>();
    boards.forEach(b -> byId.put(b.id(), b));
    Set<String> missing = store.missingAfterSweep(state.id());
    for (String id : missing) store.markStatus(driver.site(), id, PostStatus.NEEDS_CHECK);
    for (String id : missing) {
      if (!clock.instant().isBefore(deadline)) return false;
      PostState post = store.post(driver.site(), id).orElseThrow();
      Board board = byId.get(post.boardId());
      if (board == null) continue;
      confirmPost(state.id(), board, id);
      store.markSweepChecked(state.id(), id);
    }
    store.closeSweep(state.id(), true);
    return true;
  }

  private void confirmPost(long sweepId, Board board, String id) {
    Optional<String> archivedUrl = store.archivedUrl(driver.site(), id);
    FetchResult result;
    try {
      result =
          archivedUrl
              .map(url -> driver.fetchArchivedPost(http, board, id, url))
              .orElseGet(() -> driver.fetchPost(http, board, id));
    } catch (RuntimeException e) {
      failedPosts++;
      return;
    }
    switch (result) {
      case FetchResult.Found f ->
          store.storeFound(driver.site(), board.id(), id, f, clock.instant());
      case FetchResult.Deleted ignored -> {
        if (archivedUrl.isEmpty() || goneLongEnough(sweepId, id))
          store.markStatus(driver.site(), id, PostStatus.DELETED);
      }
      case FetchResult.AccessDenied ignored ->
          store.markStatus(driver.site(), id, PostStatus.ACCESS_DENIED);
      case FetchResult.Unknown ignored -> failedPosts++;
    }
  }

  /** Projected content needs a second gone verdict from a later sweep, outlasting short outages. */
  private boolean goneLongEnough(long sweepId, String id) {
    Instant now = clock.instant();
    return store
        .recordGone(driver.site(), id, sweepId, now)
        .filter(first -> !first.plus(GONE_CONFIRMATION_GAP).isAfter(now))
        .isPresent();
  }
}
