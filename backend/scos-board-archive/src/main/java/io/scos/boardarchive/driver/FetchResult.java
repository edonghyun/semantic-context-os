package io.scos.boardarchive.driver;

import java.time.Instant;
import java.util.List;

public sealed interface FetchResult {
  record Found(
      String url,
      String raw,
      String title,
      String text,
      String author,
      Instant createdAt,
      List<String> attachments,
      List<CommentData> comments,
      boolean commentsComplete)
      implements FetchResult {
    public Found {
      attachments = List.copyOf(attachments);
      comments = List.copyOf(comments);
    }
  }

  record Deleted() implements FetchResult {}

  record AccessDenied() implements FetchResult {}

  record Unknown(String reason) implements FetchResult {}
}
