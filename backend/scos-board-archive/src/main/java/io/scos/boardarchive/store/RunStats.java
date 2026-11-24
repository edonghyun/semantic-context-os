package io.scos.boardarchive.store;

import java.util.List;

public record RunStats(
    int newPosts,
    int changedPosts,
    int failedPosts,
    List<String> stoppedBoards,
    boolean sweepCompared) {
  public RunStats {
    stoppedBoards = List.copyOf(stoppedBoards);
  }
}
