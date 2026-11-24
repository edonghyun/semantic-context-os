package io.scos.boardarchive.driver;

import java.time.Instant;
import java.util.Optional;

public record PostSummary(
    String postId, String title, Instant createdAt, Optional<ChangeSignal> signal, boolean pinned) {
  public PostKey key() {
    return new PostKey(createdAt, postId);
  }
}
