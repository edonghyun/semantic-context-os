package io.scos.boardarchive.testsupport;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import java.net.URI;
import java.time.*;
import java.util.*;

public final class ScriptedDriver implements BoardDriver {
  private final LocalSite site;
  private final MutableClock clock;
  private final Map<String, List<PostSummary>> pages = new LinkedHashMap<>();
  private final Map<String, Board> boardMetadata = new HashMap<>();
  private final Map<String, FetchResult> contents = new HashMap<>();
  private final Map<String, String> articlePaths = new HashMap<>();
  private final Map<String, Integer> listStatus = new HashMap<>();
  private final Map<String, Integer> postStatus = new HashMap<>();
  private final List<String> fetched = new ArrayList<>();
  private final Map<String, RuntimeException> postFailures = new HashMap<>();
  private final Map<String, RuntimeException> listFailures = new HashMap<>();
  private int pageSize = 3;
  private Optional<RuntimeException> boardFailure = Optional.empty();
  private Duration postCost = Duration.ZERO;

  public ScriptedDriver(LocalSite site, MutableClock clock) {
    this.site = site;
    this.clock = clock;
    pages.put("a", new ArrayList<>());
  }

  @Override
  public String site() {
    return ArchiveScenario.SITE;
  }

  public void posts(String board, List<PostSummary> posts) {
    pages.put(board, new ArrayList<>(posts));
    for (PostSummary s : posts) contents.putIfAbsent(s.postId(), found(s));
  }

  public void board(Board board) {
    boardMetadata.put(board.id(), board);
    pages.putIfAbsent(board.id(), new ArrayList<>());
  }

  public FetchResult.Found found(PostSummary s) {
    return new FetchResult.Found(
        site.uri(articlePaths.getOrDefault(s.postId(), "/posts/" + s.postId())).toString(),
        "<article>Body " + s.postId() + "</article>",
        s.title(),
        "Body " + s.postId(),
        "Writer",
        s.createdAt(),
        List.of(),
        List.of(),
        true);
  }

  /** Reports this path as the article URL while fetchPost keeps reading the template route. */
  public void articlePath(String id, String path) {
    articlePaths.put(id, path);
  }

  public List<PostSummary> posts(String board) {
    return List.copyOf(pages.get(board));
  }

  public void result(String id, FetchResult result) {
    contents.put(id, result);
  }

  public void listStatus(String board, int status) {
    listStatus.put(board, status);
  }

  public void postStatus(String id, int status) {
    postStatus.put(id, status);
  }

  public void removeBoard(String board) {
    pages.remove(board);
    boardMetadata.remove(board);
  }

  public void postFailure(String id, RuntimeException failure) {
    postFailures.put(id, failure);
  }

  public void listFailure(String board, RuntimeException failure) {
    listFailures.put(board, failure);
  }

  public void boardFailure(RuntimeException failure) {
    boardFailure = Optional.of(failure);
  }

  public void clearBoardFailure() {
    boardFailure = Optional.empty();
  }

  public void pageSize(int size) {
    pageSize = size;
  }

  public void postCost(Duration cost) {
    postCost = cost;
  }

  public List<String> fetched() {
    return List.copyOf(fetched);
  }

  public void clearFetched() {
    fetched.clear();
  }

  @Override
  public List<Board> boards(Fetcher http) {
    if (boardFailure.isPresent()) throw boardFailure.get();
    return pages.keySet().stream()
        .map(id -> boardMetadata.getOrDefault(id, new Board(id, id, "")))
        .toList();
  }

  @Override
  public ListPage listPosts(Fetcher http, Board board, Optional<String> cursor) {
    String path = "/list/" + board.id() + "?offset=" + cursor.orElse("0");
    site.respond(path, listStatus.getOrDefault(board.id(), 200), "list");
    http.get(site.uri(path)).requireOk();
    if (listFailures.containsKey(board.id())) throw listFailures.get(board.id());
    int start = Integer.parseInt(cursor.orElse("0"));
    List<PostSummary> all = pages.get(board.id());
    int end = Math.min(start + pageSize, all.size());
    return new ListPage(
        all.subList(Math.min(start, all.size()), end),
        end < all.size() ? Optional.of(Integer.toString(end)) : Optional.empty());
  }

  @Override
  public FetchResult fetchPost(Fetcher http, Board board, String id) {
    return read(http, id, site.uri("/posts/" + id));
  }

  @Override
  public FetchResult fetchArchivedPost(Fetcher http, Board board, String id, String archivedUrl) {
    return read(http, id, URI.create(archivedUrl));
  }

  private FetchResult read(Fetcher http, String id, URI uri) {
    site.respond(uri.getPath(), postStatus.getOrDefault(id, 200), "post");
    Response response = http.get(uri);
    clock.advance(postCost);
    fetched.add(id);
    if (postFailures.containsKey(id)) throw postFailures.get(id);
    if (response.status() == 404) return new FetchResult.Deleted();
    if (response.status() == 403) return new FetchResult.AccessDenied();
    if (response.status() < 200 || response.status() >= 300)
      return new FetchResult.Unknown("HTTP " + response.status());
    return contents.getOrDefault(id, new FetchResult.Unknown("unregistered post"));
  }
}
