package io.scos.boardarchive.crawl;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.fetch.*;
import io.scos.boardarchive.store.*;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testsupport.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

final class CrawlScenario implements AutoCloseable {
  final ArchiveScenario archive;
  final LocalSite site;
  final HttpClient client = HttpClient.newHttpClient();
  final ScriptedDriver driver;
  final PoliteFetcher http;
  CrawlSettings settings = CrawlSettings.defaults(Duration.ofHours(1));

  CrawlScenario(Path file) throws Exception {
    archive = new ArchiveScenario(file);
    site = new LocalSite();
    driver = new ScriptedDriver(site, archive.clock);
    http =
        new PoliteFetcher(
            client,
            archive.clock,
            archive.clock::advance,
            SitePolicy.defaults("archive-test"),
            new FetchLog());
  }

  void givenFivePosts() {
    driver.posts(
        "a",
        List.of(
            postAt("5", "00:05"),
            postAt("4", "00:04"),
            postAt("3", "00:03"),
            postAt("2", "00:02"),
            postAt("1", "00:01")));
  }

  // Times without a date belong to the fixture day, in UTC.
  PostSummary postAt(String id, String time) {
    return postAt(
        id, LocalDate.of(2026, 11, 17).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC));
  }

  PostSummary postAt(String id, Instant at) {
    return new PostSummary(
        id,
        "Title " + id,
        at,
        Optional.of(new ChangeSignal(Optional.empty(), OptionalInt.of(0))),
        false);
  }

  PostSummary postWithCommentsAt(String id, String time, int comments) {
    PostSummary post = postAt(id, time);
    return new PostSummary(
        id,
        post.title(),
        post.createdAt(),
        Optional.of(new ChangeSignal(Optional.empty(), OptionalInt.of(comments))),
        false);
  }

  PostSummary postWithoutSignalAt(String id, String time) {
    PostSummary post = postAt(id, time);
    return new PostSummary(id, post.title(), post.createdAt(), Optional.empty(), false);
  }

  PostSummary pinnedPostAt(String id, String time) {
    PostSummary post = postAt(id, time);
    return new PostSummary(id, post.title(), post.createdAt(), post.signal(), true);
  }

  void givenArchivedPosts() {
    givenFivePosts();
    archive.store.upsertBoards(
        driver.site(), List.of(new Board("a", "a", "")), archive.clock.instant());
    for (PostSummary p : driver.posts("a")) givenArchivedPost(p);
    archive.store.saveCursors(
        driver.site(),
        "a",
        new BoardCursors(Optional.of(postAt("5", "00:05").key()), Optional.empty(), true));
  }

  void givenArchivedPost(PostSummary post) {
    archive.store.recordSummary(driver.site(), "a", post, archive.clock.instant());
    archive.store.storeFound(
        driver.site(), "a", post.postId(), driver.found(post), archive.clock.instant());
  }

  void givenPendingBodyWithSameSignal(PostSummary pending) {
    givenArchivedPost(postAt(pending.postId(), pending.createdAt()));
    archive.store.recordSummary(driver.site(), "a", pending, archive.clock.instant());
    var saved =
        archive
            .store
            .post(driver.site(), pending.postId())
            .orElseThrow(() -> new IllegalStateException("given 준비 실패: 보관된 글이 있어야 한다"));
    if (saved.lastFetchedAt().isEmpty()
        || !saved.lastSignal().equals(pending.signal())
        || !archive.store.needsFetch(driver.site(), pending.postId()))
      throw new IllegalStateException("given 준비 실패: 보관된 글은 같은 신호의 본문 재요청만 남아야 한다");
  }

  void givenListingWithinBudget(Duration budget, PostSummary... posts) {
    driver.posts("a", List.of(posts));
    settings = CrawlSettings.defaults(budget);
  }

  void daysLater(int days) {
    archive.clock.advance(Duration.ofDays(days));
  }

  RunStats run() {
    return new CrawlRunner(archive.store, driver, http, archive.clock, settings, http.log()).run();
  }

  List<RunStats> runCycles(int count) {
    List<RunStats> runs = new ArrayList<>();
    for (int i = 0; i < count; i++) runs.add(run());
    return List.copyOf(runs);
  }

  List<List<String>> fetchedPerRun(int count, Duration interval) {
    List<List<String>> requests = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      archive.clock.advance(interval);
      driver.clearFetched();
      run();
      requests.add(driver.fetched());
    }
    return List.copyOf(requests);
  }

  RunStats runViaWrapper() {
    return new CrawlRunner(
            archive.store, driver, new ForwardingFetcher(), archive.clock, settings, http.log())
        .run();
  }

  private final class ForwardingFetcher implements Fetcher {
    @Override
    public Response get(URI uri) {
      return http.get(uri);
    }
  }

  PostStatus status(String id) {
    return archive.store.post(driver.site(), id).orElseThrow().status();
  }

  List<String> requestsDuring(Runnable action) {
    return site.requestsDuring(action);
  }

  long recordedContentRequestsDuring(Runnable action) throws SQLException {
    var requests = site.requestsDuring(action);
    long otherRequests =
        requests.stream()
            .filter(path -> !path.startsWith("/list/") && !path.startsWith("/posts/"))
            .count();
    return archive.lastRun().requests() - otherRequests;
  }

  boolean bodyFetched(String postId) {
    return archive.store.post(driver.site(), postId).flatMap(PostState::lastFetchedAt).isPresent();
  }

  Map<String, Integer> revisionCounts() throws SQLException {
    return archive.revisionCounts();
  }

  @Override
  public void close() {
    client.close();
    site.close();
    archive.close();
  }

  boolean sweep() {
    return new SweepPass(archive.store, driver, http, archive.clock)
        .run(archive.clock.instant().plusSeconds(3600));
  }

  boolean shortSweep() {
    return new SweepPass(archive.store, driver, http, archive.clock)
        .run(archive.clock.instant().plusSeconds(6));
  }

  void givenMissingPost(FetchResult result) {
    givenArchivedPosts();
    driver.posts(
        "a",
        List.of(
            postAt("4", "00:04"),
            postAt("3", "00:03"),
            postAt("2", "00:02"),
            postAt("1", "00:01")));
    driver.result("5", result);
  }

  void givenMissingPostArchivedAt(String path) {
    driver.articlePath("5", path);
    givenMissingPost(new FetchResult.Deleted());
  }

  void givenMissingPostSeenDeletedOnce() {
    givenMissingPost(new FetchResult.Deleted());
    sweep();
    requireStatus("5", PostStatus.NEEDS_CHECK, "첫 삭제 확인 뒤 확인 필요여야 한다");
  }

  void givenMissingPostDeletedThenFound() {
    givenMissingPostSeenDeletedOnce();
    driver.result("5", driver.found(postAt("5", "00:05")));
    sweep();
    requireStatus("5", PostStatus.NORMAL, "삭제 확인 뒤 본문을 다시 읽어야 한다");
    driver.result("5", new FetchResult.Deleted());
  }

  void givenMissingPostDeletedThenRelisted() {
    givenMissingPostSeenDeletedOnce();
    List<PostSummary> missing = driver.posts("a");
    driver.posts("a", List.of(postAt("5", "00:05")));
    sweep();
    requireStatus("5", PostStatus.NORMAL, "삭제 확인 뒤 목록에서 다시 보여야 한다");
    driver.posts("a", missing);
  }

  void givenMissingUnfetchedPost(String id, String time) {
    givenArchivedPosts();
    archive.store.recordSummary(driver.site(), "a", postAt(id, time), archive.clock.instant());
    driver.result(id, new FetchResult.Deleted());
  }

  void hoursLater(int hours) {
    archive.clock.advance(Duration.ofHours(hours));
  }

  private void requireStatus(String id, PostStatus expected, String reason) {
    if (status(id) != expected) throw new IllegalStateException("given 준비 실패: " + reason);
  }

  boolean sweepForSeconds(int seconds) {
    return new SweepPass(archive.store, driver, http, archive.clock)
        .run(archive.clock.instant().plusSeconds(seconds));
  }
}
