package io.scos.boardarchive.driver;

import java.time.Instant;

public record PostKey(Instant createdAt, String postId) implements Comparable<PostKey> {
  @Override
  public int compareTo(PostKey other) {
    int time = createdAt.compareTo(other.createdAt);
    return time == 0 ? postId.compareTo(other.postId) : time;
  }
}
