package io.scos.boardarchive.driver;

import java.util.List;
import java.util.Optional;

public record ListPage(List<PostSummary> posts, Optional<String> nextCursor) {
  public ListPage {
    posts = List.copyOf(posts);
  }
}
