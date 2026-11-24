package io.scos.boardarchive.testkit.consumer;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.profile.ProfileDriver;
import io.scos.boardarchive.testkit.LocalSite;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Exercises remote board discovery and requires the discovered board metadata on later calls. */
final class RemoteBoardsDriver implements BoardDriver {
  private final ProfileDriver delegate;
  private final LocalSite site;
  private final Set<String> observed = new HashSet<>();

  RemoteBoardsDriver(ProfileDriver delegate, LocalSite site) {
    this.delegate = delegate;
    this.site = site;
  }

  @Override
  public String site() {
    return delegate.site();
  }

  @Override
  public List<Board> boards(Fetcher http) {
    http.get(site.uri("/boards")).requireOk();
    return delegate.boards(http).stream().map(b -> new Board(b.id(), b.name(), "")).toList();
  }

  @Override
  public ListPage listPosts(Fetcher http, Board board, Optional<String> cursor) {
    requireConfiguredBoard(board);
    ListPage page = delegate.listPosts(http, board, cursor);
    page.posts().forEach(p -> observed.add(p.postId()));
    return page;
  }

  @Override
  public FetchResult fetchPost(Fetcher http, Board board, String postId) {
    requireConfiguredBoard(board);
    if (!observed.contains(postId)) return new FetchResult.Unknown("Unobserved post URL");
    FetchResult result = delegate.fetchPost(http, board, postId);
    if (!(result instanceof FetchResult.Found found)) return result;
    Response comments = http.get(site.uri("/comments/" + postId + "?page=1"));
    return new FetchResult.Found(
        found.url(),
        found.raw(),
        found.title(),
        found.text(),
        found.author(),
        found.createdAt(),
        found.attachments(),
        found.comments(),
        comments.status() >= 200 && comments.status() < 300);
  }

  private void requireConfiguredBoard(Board board) {
    if (!List.of(new Board("a", "General", ""), new Board("b", "Other", "")).contains(board))
      throw new IllegalArgumentException("Unknown board metadata: " + board);
  }
}
