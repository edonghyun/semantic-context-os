package io.scos.boardarchive.driver;

import java.util.List;
import java.util.Optional;

/** Lists newest first; post ids must be unique within the site. */
public interface BoardDriver {
  String site();

  List<Board> boards(Fetcher http);

  ListPage listPosts(Fetcher http, Board board, Optional<String> cursor);

  FetchResult fetchPost(Fetcher http, Board board, String postId);

  /**
   * Rechecks a post that vanished from a complete listing. {@code archivedUrl} is the Found.url
   * stored with its latest revision; a driver whose Found.url is the URL it fetched may treat a 404
   * there as deletion evidence. Decorators must forward this method.
   */
  default FetchResult fetchArchivedPost(
      Fetcher http, Board board, String postId, String archivedUrl) {
    return fetchPost(http, board, postId);
  }
}
