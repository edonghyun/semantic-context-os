package io.scos.boardarchive.testkit;

import io.scos.boardarchive.driver.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.function.BiFunction;

final class BoardDriverScenario implements AutoCloseable {
  private final LocalSite site = new LocalSite();
  private final MutableClock clock = new MutableClock();
  private final HttpClient client = HttpClient.newHttpClient();
  private final BiFunction<LocalSite, MutableClock, BoardDriver> createDriver;
  private Fetcher http = this::get;
  private BoardDriver driver;
  private final Map<String, Board> boards = new HashMap<>();

  BoardDriverScenario(BiFunction<LocalSite, MutableClock, BoardDriver> createDriver)
      throws IOException {
    this.createDriver = createDriver;
    try {
      givenNewDriver();
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    }
  }

  List<Board> boards() {
    return driver.boards(http);
  }

  ListPage firstPage(String board) {
    return driver.listPosts(http, boards.get(board), Optional.empty());
  }

  ListPage nextPage(String board, String cursor) {
    return driver.listPosts(http, boards.get(board), Optional.of(cursor));
  }

  void givenObservedPost(String board, String id) {
    ListPage page = firstPage(board);
    if (page.posts().stream().noneMatch(p -> p.postId().equals(id)))
      throw new IllegalStateException("given 준비 실패: 첫 쪽에서 본문 대상 글을 관측해야 한다: " + id);
  }

  FetchResult fetch(String board, String id) {
    return driver.fetchPost(http, boards.get(board), id);
  }

  FetchResult fetchArchived(String board, String id, String archivedPath) {
    return driver.fetchArchivedPost(http, boards.get(board), id, url(archivedPath));
  }

  void givenResponse(String path, int status) {
    site.respond(path, status, "failure");
  }

  void givenNewDriver() {
    driver = createDriver.apply(site, clock);
    boards.clear();
    boards().forEach(b -> boards.put(b.id(), b));
  }

  void givenFetcherFailure(RuntimeException failure) {
    http =
        uri -> {
          throw failure;
        };
  }

  String url(String path) {
    return site.uri(path).toString();
  }

  List<String> requestsDuring(Runnable action) {
    return site.requestsDuring(action);
  }

  // The driver contract observes wire statuses before politeness/retry policy is applied.
  private Response get(URI uri) {
    if (!uri.getRawAuthority().equals(site.uri("/").getRawAuthority()))
      throw new IllegalArgumentException("Only the local fixture origin is allowed");
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Local fixture request interrupted", e);
    }
  }

  @Override
  public void close() {
    client.close();
    site.close();
  }
}
