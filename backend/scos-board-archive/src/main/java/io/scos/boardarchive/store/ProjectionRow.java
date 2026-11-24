package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.CommentData;
import java.time.Instant;
import java.util.*;

/** Snapshot of archived content needed to render one searchable document. */
public record ProjectionRow(
    String postId,
    String boardName,
    Optional<String> projectedHash,
    String url,
    String title,
    String text,
    Instant createdAt,
    Instant fetchedAt,
    List<CommentData> comments) {
  public ProjectionRow {
    comments = List.copyOf(comments);
  }
}
