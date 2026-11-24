package io.scos.boardarchive.store;

import java.time.Instant;
import java.util.*;

public record SweepState(
    long id,
    Instant startedAt,
    Map<String, Optional<String>> boardCursors,
    Set<String> completeBoards) {
  public SweepState {
    boardCursors = Map.copyOf(boardCursors);
    completeBoards = Set.copyOf(completeBoards);
  }
}
