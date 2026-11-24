package io.scos.boardarchive.fetch;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

@DisplayName("정중한 요청은 간격과 재시도와 robots를 지킨다")
class PoliteFetcherTest {
  private static final Duration MAX_ACCEPTABLE_WAIT_ONE_MINUTE = Duration.ofMinutes(1);
  private static final Duration ROBOTS_CACHE_ONE_DAY = Duration.ofDays(1);
  private static final Duration MIN_INTERVAL = Duration.ofSeconds(3);
  private static final Duration TOO_SHORT = Duration.ofSeconds(2);
  FetchScenario s;

  @BeforeEach
  void setup() throws Exception {
    s = new FetchScenario();
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("연속 요청 사이 최소 간격을 기다린다")
  void secondRequestWaitsMinInterval() {
    // given
    s.givenResponses(200);
    s.get("/post");
    Instant before = s.clock.instant();
    // when
    s.get("/post");
    // then
    assertThat(Duration.between(before, s.clock.instant())).isGreaterThanOrEqualTo(MIN_INTERVAL);
  }

  @Test
  @DisplayName("3초 미만 요청 간격을 거부한다")
  void minIntervalBelowThreeSecondsIsRejected() {
    // when·then
    assertThatThrownBy(() -> new SitePolicy("test", TOO_SHORT, 4, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid politeness policy");
  }

  @Test
  @DisplayName("429 뒤 재시도 성공과 모든 상태를 기록한다")
  void retries429Then200() {
    // given
    s.givenResponses(429, 200);
    // when
    s.get("/post");
    // then
    assertThat(s.log.byStatus())
        .containsExactlyInAnyOrderEntriesOf(Map.of(404, 1L, 429, 1L, 200, 1L));
  }

  @Test
  @DisplayName("4회 재시도 뒤 전송 실패를 알린다")
  void givesUpAfter4Retries() {
    // given
    s.givenResponses(503, 503, 503, 503, 503);
    // then
    assertThatThrownBy(() -> s.get("/post"))
        .isInstanceOf(TransportFailureException.class)
        .hasMessage("Retry budget exhausted");
    assertThat(s.site.requests()).filteredOn(p -> p.equals("/post")).hasSize(5);
  }

  @Test
  @DisplayName("404 응답은 재시도 없이 반환한다")
  void status404IsReturnedNotRetried() {
    // given
    s.givenResponses(404);
    // when
    var result = s.get("/post");
    // then
    assertThat(result.status()).isEqualTo(404);
    assertThat(s.log.requests()).isEqualTo(2);
  }

  @Test
  @DisplayName("금지된 경로로 요청하지 않는다")
  void robotsDisallowedPathIsNotRequested() {
    // given
    s.givenRobots("User-agent: *\nDisallow: /private");
    // then
    assertThatThrownBy(() -> s.get("/private/x"))
        .isInstanceOf(RobotsDisallowedException.class)
        .hasMessage("Path disallowed by robots policy");
    assertThat(s.site.requests()).containsExactly("/robots.txt");
  }

  @Test
  @DisplayName("robots 서버 오류이면 전부 금지한다")
  void robots5xxDeniesAll() {
    // given
    s.site.respond("/robots.txt", 503, "");
    // then
    assertThatThrownBy(() -> s.get("/post"))
        .isInstanceOf(RobotsDisallowedException.class)
        .hasMessage("Path disallowed by robots policy");
  }

  @Test
  @DisplayName("robots 404이면 전부 허용한다")
  void robots404AllowsAll() {
    // given
    s.givenResponses(200);
    // when
    var result = s.get("/post");
    // then
    assertThat(result.status()).isEqualTo(200);
  }

  @Test
  @DisplayName("허락 예외는 금지 경로를 허용하고 근거를 기록한다")
  void permissionAllowsDisallowedPathAndIsLogged() {
    // given
    s.givenRobots("User-agent: *\nDisallow: /private");
    s.givenPermission();
    s.site.respond("/private", 200, "ok");
    // when
    s.get("/private");
    // then
    assertThat(s.log.robotsExceptionUsed())
        .get()
        .extracting(Permission::evidence)
        .isEqualTo("local permission record");
  }

  @Test
  @DisplayName("허락 예외를 쓰지 않은 다음 실행에는 근거를 복사하지 않는다 (§4)")
  void unusedPermissionDoesNotLeakIntoNextRun() {
    // given
    s.givenRobots("User-agent: *\nDisallow: /private");
    s.givenPermission();
    s.site.respond("/private", 200, "ok");
    s.site.respond("/public", 200, "ok");
    s.get("/private");
    FetchLog before = s.log.snapshot();
    // when
    s.get("/public");
    // then
    assertThat(s.log.since(before).robotsExceptionUsed()).isEmpty();
  }

  @Test
  @DisplayName("같은 허락을 다음 실행에도 실제 사용하면 근거를 기록한다 (§4)")
  void repeatedPermissionUseIsLoggedForEachRun() {
    // given
    s.givenRobots("User-agent: *\nDisallow: /private");
    s.givenPermission();
    s.site.respond("/private", 200, "ok");
    s.get("/private");
    FetchLog before = s.log.snapshot();
    // when
    s.get("/private");
    // then
    assertThat(s.log.since(before).robotsExceptionUsed()).isPresent();
  }

  @Test
  @DisplayName("하루를 요구하는 Retry-After는 기다리지 않고 실패한다 (§4)")
  void excessiveRetryAfterIsRefused() {
    // given
    s.site.respond("/post", 429, "busy", Map.of("Retry-After", "86400"));
    Instant before = s.clock.instant();
    // when·then
    assertThatThrownBy(() -> s.get("/post"))
        .isInstanceOf(TransportFailureException.class)
        .hasMessage("Retry-After exceeds one minute");
    assertThat(Duration.between(before, s.clock.instant()))
        .isLessThan(MAX_ACCEPTABLE_WAIT_ONE_MINUTE);
  }

  @Test
  @DisplayName("일시 금지된 robots도 24시간 뒤 다시 읽는다 (§4)")
  void expiredRobotsFailureIsReloaded() {
    // given
    s.site.respond("/robots.txt", 503, "");
    assertThatThrownBy(() -> s.get("/post"))
        .isInstanceOf(RobotsDisallowedException.class)
        .hasMessage("Path disallowed by robots policy");
    s.site.respond("/robots.txt", 404, "");
    s.givenResponses(200);
    s.clock.advance(ROBOTS_CACHE_ONE_DAY);
    // when
    var response = s.get("/post");
    // then
    assertThat(response.status()).isEqualTo(200);
  }
}
