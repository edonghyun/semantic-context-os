package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.PostKey;
import java.util.Optional;

/**
 * Durable in-board scan position; cursor overlaps one page; continuation preserves a scan suspended
 * for fresh FRONT work.
 *
 * @param after retained for stored-format compatibility; not used to decide which posts to visit
 */
public record CrawlCheckpoint(
    Phase phase,
    String driverCursor,
    Optional<PostKey> after,
    Optional<PostKey> boundary,
    Optional<CrawlCheckpoint> continuation) {
  public CrawlCheckpoint(
      Phase phase, String driverCursor, Optional<PostKey> after, Optional<PostKey> boundary) {
    this(phase, driverCursor, after, boundary, Optional.empty());
  }

  /** Fresh posts precede either suspended scan; the suspended position remains durable. */
  public CrawlCheckpoint prioritizeFront(Optional<PostKey> front) {
    if (front.isEmpty() || phase == Phase.FRONT || phase == Phase.DONE) return this;
    return new CrawlCheckpoint(Phase.FRONT, "", Optional.empty(), front, Optional.of(this));
  }

  /**
   * BACKFILL continuations wait for RECHECK and deferred bodies; other continuations resume in
   * place.
   */
  public CrawlCheckpoint nextPhase(BoardCursors cursors) {
    if (continuation.isPresent()
        && (phase == Phase.PENDING
            || (phase == Phase.FRONT && continuation.get().phase() != Phase.BACKFILL)))
      return continuation.get();
    Phase next =
        switch (phase) {
          case FRONT -> boundary.isEmpty() ? Phase.DONE : Phase.RECHECK;
          case RECHECK -> Phase.PENDING;
          case PENDING -> cursors.backfillDone() ? Phase.DONE : Phase.BACKFILL;
          case BACKFILL, DONE -> Phase.DONE;
        };
    if (next == Phase.RECHECK || next == Phase.PENDING)
      return new CrawlCheckpoint(next, "", Optional.empty(), Optional.empty(), continuation);
    return new CrawlCheckpoint(
        next,
        next == Phase.BACKFILL ? cursors.back().map(BackfillCursor::driverCursor).orElse("") : "",
        next == Phase.BACKFILL ? cursors.back().map(BackfillCursor::key) : Optional.empty(),
        Optional.empty());
  }

  public enum Phase {
    FRONT,
    RECHECK,
    PENDING,
    BACKFILL,
    DONE
  }
}
