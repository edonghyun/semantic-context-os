package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.fetch.RobotsDisallowedException;
import io.scos.boardarchive.fetch.TransportFailureException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("게시판 드라이버는 수집기의 목록과 본문 계약을 지킨다 (BoardDriver)")
public abstract class BoardDriverContract {
  private BoardDriverScenario s;

  final BoardDriverScenario scenario() {
    return s;
  }

  static BoardDriverContract requireBase(Object selection) {
    if (selection instanceof BoardDriverContract contract) return contract;
    throw new IllegalStateException("given 준비 실패: 선택 계약은 BoardDriverContract 하위 클래스에서 구현한다");
  }

  protected abstract BoardDriver createDriver(LocalSite site, MutableClock clock);

  /** Maps only the logical fixture identity; titles, dates, order and outcomes are fixed. */
  protected abstract String postId(String board, int number);

  /** Maps the first-page request to an origin-relative path, including its query. */
  protected abstract String listPath(String board);

  /** Maps a logical fixture post to its origin-relative request path, including its query. */
  protected abstract String postPath(String board, int number);

  /** Ordered origin-relative requests after the post itself; each path is requested once. */
  protected List<String> companionPaths(String board, int number) {
    return List.of();
  }

  /**
   * Makes listPath("a") answer with this status. A fake that scripts its own status overrides it.
   */
  protected void givenListStatus(int status) {
    givenResponse(listPath("a"), status);
  }

  /**
   * Makes postPath("a", 12) answer with this status. A fake that scripts its own status overrides
   * it.
   */
  protected void givenPostStatus(int status) {
    givenResponse(postPath("a", 12), status);
  }

  /** Registers a raw HTTP response for the local fixture. */
  protected final void givenResponse(String path, int status) {
    s.givenResponse(path, status);
  }

  @BeforeEach
  protected final void setup() throws Exception {
    requireSelections();
    requireInheritedCases();
    s = new BoardDriverScenario(this::createDriver);
  }

  private void requireSelections() {
    if ((this instanceof StaticBoardsContract) == (this instanceof RemoteBoardsContract))
      throw new IllegalStateException(
          "given 준비 실패: StaticBoardsContract와 RemoteBoardsContract 중 정확히 하나를 선택한다");
    if (!companionPaths("a", 12).isEmpty() != (this instanceof CompanionRequestsContract))
      throw new IllegalStateException(
          "given 준비 실패: 부속 경로 선언과 CompanionRequestsContract 선택이 일치해야 한다");
  }

  private void requireInheritedCases() throws NoSuchMethodException {
    for (Class<?> selection :
        List.of(
            StaticBoardsContract.class,
            RemoteBoardsContract.class,
            CategorizedBoardsContract.class,
            CompanionRequestsContract.class,
            ArchivedPostContract.class)) {
      if (!selection.isInstance(this)) continue;
      for (Method method : selection.getDeclaredMethods()) {
        if (method.isDefault()
            && getClass()
                    .getMethod(method.getName(), method.getParameterTypes())
                    .getDeclaringClass()
                != selection)
          throw new IllegalStateException("given 준비 실패: 선택 계약 사례 " + method.getName() + "를 재정의했다");
      }
    }
  }

  @AfterEach
  protected final void close() {
    if (s != null) s.close();
  }

  @Test
  @DisplayName("설정한 게시판의 id와 이름을 순서대로 돌려준다")
  protected final void returnsConfiguredBoards() {
    // when
    var boards = s.boards();
    // then
    assertThat(boards)
        .extracting(Board::id, Board::name)
        .containsExactly(tuple("a", "General"), tuple("b", "Other"));
  }

  @Test
  @DisplayName("첫 쪽은 글 id와 제목과 작성 시각과 고정 여부를 돌려준다")
  protected final void firstPageHasPostSummaries() {
    // when
    var page = s.firstPage("a");
    // then
    assertThat(page.posts())
        .containsExactly(
            new PostSummary(
                postId("a", 14),
                "Notice",
                Instant.parse("2026-11-14T15:00:00Z"),
                Optional.empty(),
                true),
            new PostSummary(
                postId("a", 12),
                "First",
                Instant.parse("2026-11-16T15:00:00Z"),
                Optional.empty(),
                false),
            new PostSummary(
                postId("a", 11),
                "Second",
                Instant.parse("2026-11-15T15:00:00Z"),
                Optional.empty(),
                false));
  }

  @Test
  @DisplayName("첫 쪽의 서로 다른 글은 서로 다른 id를 가진다")
  protected final void firstPagePostIdsAreUnique() {
    // when
    var page = s.firstPage("a");
    // then
    assertThat(page.posts()).extracting(PostSummary::postId).doesNotHaveDuplicates();
  }

  @ParameterizedTest(name = "목록 HTTP {0}")
  @ValueSource(ints = {302, 403, 404, 500})
  @DisplayName("목록이 비정상 응답이면 상태 코드를 담은 예외를 던진다")
  protected final void listFailureIncludesStatus(int status) {
    // given
    givenListStatus(status);
    // when·then
    assertThatThrownBy(() -> s.firstPage("a"))
        .isInstanceOfSatisfying(
            HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(status));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fetcherFailures")
  @DisplayName("목록 요청이 예외로 끝나면 같은 예외를 던진다")
  protected final void listingPropagatesFetcherFailure(RuntimeException failure) {
    // given
    s.givenFetcherFailure(failure);
    // when·then
    assertThatThrownBy(() -> s.firstPage("a")).isSameAs(failure);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fetcherFailures")
  @DisplayName("본문 요청이 예외로 끝나면 같은 예외를 던진다")
  protected final void fetchingPropagatesFetcherFailure(RuntimeException failure) {
    // given
    s.givenObservedPost("a", postId("a", 12));
    s.givenFetcherFailure(failure);
    // when·then
    assertThatThrownBy(() -> s.fetch("a", postId("a", 12))).isSameAs(failure);
  }

  protected static final Stream<RuntimeException> fetcherFailures() {
    return Stream.of(
        new TransportFailureException("injected transport failure"),
        new RobotsDisallowedException());
  }

  @Test
  @DisplayName("본문이 200이면 원문 URL과 제목과 작성 시각을 담은 Found다")
  protected final void successfulPostIsFound() {
    // given
    s.givenObservedPost("a", postId("a", 12));
    // when
    var result = s.fetch("a", postId("a", 12));
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f)
                    .extracting(
                        FetchResult.Found::url,
                        FetchResult.Found::title,
                        FetchResult.Found::createdAt)
                    .containsExactly(
                        s.url(postPath("a", 12)), "First", Instant.parse("2026-11-16T15:00:00Z")));
  }

  @Test
  @DisplayName("다른 게시판의 글은 그 글의 원문 URL과 제목으로 돌려준다")
  protected final void secondBoardPostUsesItsOwnPath() {
    // given
    s.givenObservedPost("b", postId("b", 12));
    // when
    var result = s.fetch("b", postId("b", 12));
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f)
                    .extracting(
                        FetchResult.Found::url,
                        FetchResult.Found::title,
                        FetchResult.Found::createdAt)
                    .containsExactly(
                        s.url(postPath("b", 12)),
                        "Other first",
                        Instant.parse("2026-11-16T15:00:00Z")));
  }

  @Test
  @DisplayName("댓글 없는 본문은 완전한 빈 댓글 목록을 돌려준다")
  protected final void noCommentsIsCompleteEmptyList() {
    // given
    s.givenObservedPost("a", postId("a", 12));
    // when
    var result = s.fetch("a", postId("a", 12));
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f)
                    .extracting(FetchResult.Found::comments, FetchResult.Found::commentsComplete)
                    .containsExactly(List.of(), true));
  }

  @Test
  @DisplayName("본문이 404이면 Deleted다")
  protected final void missingPostIsDeleted() {
    // given
    s.givenObservedPost("a", postId("a", 12));
    givenPostStatus(404);
    // when
    var result = s.fetch("a", postId("a", 12));
    // then
    assertThat(result).isInstanceOf(FetchResult.Deleted.class);
  }

  @Test
  @DisplayName("본문이 403이면 AccessDenied다")
  protected final void forbiddenPostIsAccessDenied() {
    // given
    s.givenObservedPost("a", postId("a", 12));
    givenPostStatus(403);
    // when
    var result = s.fetch("a", postId("a", 12));
    // then
    assertThat(result).isInstanceOf(FetchResult.AccessDenied.class);
  }

  @ParameterizedTest(name = "본문 HTTP {0}")
  @ValueSource(ints = {302, 500})
  @DisplayName("삭제와 접근 거부 외 비정상 본문은 Unknown이다")
  protected final void otherPostFailureIsUnknown(int status) {
    // given
    s.givenObservedPost("a", postId("a", 12));
    givenPostStatus(status);
    // when
    var result = s.fetch("a", postId("a", 12));
    // then
    assertThat(result).isInstanceOf(FetchResult.Unknown.class);
  }

  @Test
  @DisplayName("목록을 한 번 읽으면 목록 HTTP 요청은 한 건이다")
  protected final void listingMakesOneRequest() {
    // when
    var requests = s.requestsDuring(() -> s.firstPage("a"));
    // then
    assertThat(requests).containsExactly(listPath("a"));
  }

  @ParameterizedTest(name = "게시판 {0}")
  @ValueSource(strings = {"a", "b"})
  @DisplayName("본문은 선언한 본문과 부속 경로를 순서대로 각 한 번 요청한다")
  protected final void fetchingUsesDeclaredRequests(String board) {
    // given
    s.givenObservedPost(board, postId(board, 12));
    var expected = new ArrayList<String>();
    expected.add(postPath(board, 12));
    expected.addAll(companionPaths(board, 12));
    // when
    var requests = s.requestsDuring(() -> s.fetch(board, postId(board, 12)));
    // then
    assertThat(requests).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("두 게시판의 원문 id가 같아도 사이트 안 글 id는 구분된다")
  protected final void postIdsAreUniqueWithinSite() {
    // given
    var first = s.firstPage("a");
    if (!first.posts().stream().map(PostSummary::postId).toList().contains(postId("a", 12)))
      throw new IllegalStateException("given 준비 실패: 첫 게시판의 원문 12가 있어야 한다");
    // when
    var second = s.firstPage("b");
    // then
    assertThat(second.posts())
        .extracting(PostSummary::postId)
        .containsExactly(postId("b", 12))
        .doesNotContainAnyElementsOf(first.posts().stream().map(PostSummary::postId).toList());
  }
}
