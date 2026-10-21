package io.scos.connector.slack.internal;

import static io.scos.connector.slack.internal.SlackScenario.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.slack.internal.SlackScenario.Collection;
import io.scos.connector.slack.internal.SlackScenario.TsCollection;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * F12 동등성: 가짜 Slack 서버(harness/fixtures/p4/slack-dataset.json)에서 Java SlackAdapter가 만든
 * 페이지·항목·체크포인트가 TS SlackAdapter의 결과(expected-raw-items.json)와 같다. 재개·스냅샷·오류 분류도 본다.
 */
@DisplayName("Slack 어댑터는 TS SlackAdapter와 같은 페이지·원본·체크포인트를 낸다(F12)")
class SlackAdapterFixtureTest {
  static final String HISTORY = "/api/conversations.history";
  static final String REPLIES = "/api/conversations.replies";
  static final String USERS_CONVERSATIONS = "/api/users.conversations";
  static final String FIRST_CHANNEL = "C0000000001";
  static final String SECOND_CHANNEL = "C0000000002";

  /** 첫 채널에서 reply_count 2를 가진 스레드 루트(slack-dataset.json). */
  static final String THREAD_TS = "1760400100.000000";

  /** 지터 0의 첫 backoff — cap 4s의 하한. */
  static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(2);

  /**
   * ratelimited 응답의 Retry-After(초). backoff 범위 [2s, 4s)와 겹치지 않아야 헤더를 무시하고 backoff로 기다리는 구현이 드러난다.
   */
  static final int RETRY_AFTER_SECONDS = 7;

  static final Duration RETRY_AFTER = Duration.ofSeconds(RETRY_AFTER_SECONDS);

  /** 재시도 상한 5회 사이의 대기 수. */
  static final int WAITS_BEFORE_GIVING_UP = 4;

  final SlackScenario slack = new SlackScenario();
  final TsCollection ts = new TsCollection();

  @AfterEach
  void tearDown() {
    slack.close();
  }

  @Nested
  @DisplayName("데이터셋 토큰으로 처음부터 수집하면")
  class FirstCollection {
    @Test
    @DisplayName("토큰을 Bearer Authorization 헤더로 보낸다")
    void sendsBearerToken() {
      // when
      slack.collect();

      // then
      assertThat(slack.authorizations()).isNotEmpty().containsOnly(slack.bearerHeader());
    }

    @Test
    @DisplayName("토큰을 쿼리스트링에 싣지 않는다")
    void keepsTokenOutOfQuery() {
      // when
      slack.collect();

      // then
      assertThat(slack.requests()).isNotEmpty().noneMatch(r -> r.contains(slack.datasetToken()));
    }

    @Test
    @DisplayName("TS와 같은 수의 페이지를 낸다")
    void pages() {
      // when
      Collection java = slack.collect();

      // then
      assertThat(java.pages()).hasSize(ts.pagesApplied());
    }

    @Test
    @DisplayName("TS와 같은 수의 upsert를 페이지에 싣는다(root 중복 포함)")
    void upserts() {
      // when
      Collection java = slack.collect();

      // then
      assertThat(java.upserts()).isEqualTo(ts.pageUpserts());
    }

    @Test
    @DisplayName("항목마다 종류·버전·순서·메타데이터·본문·관측 시각이 TS와 같다")
    void items() {
      // when
      Collection java = slack.collect();

      // then
      assertThat(java.lastItems()).containsExactlyInAnyOrderElementsOf(ts.items());
    }

    @Test
    @DisplayName("거부 항목 하나를 TS와 같은 종류·위치로 남긴다")
    void rejection() {
      // when
      Collection java = slack.collect();

      // then
      assertThat(java.rejections())
          .singleElement()
          .extracting(
              r -> r.failureKind().wire(),
              r -> JSON.readTree(r.providerLocatorJson().orElseThrow()))
          .containsExactly(ts.rejectionKind(), ts.rejectionLocator());
    }

    @Test
    @DisplayName("최종 체크포인트는 TS와 같다(워터마크 2)")
    void finalCheckpoint() {
      // when
      Checkpoint last = slack.collect().finalCheckpoint();

      // then
      assertThat(last)
          .extracting(Checkpoint::schemaVersion, c -> JSON.readTree(c.valueJson()))
          .containsExactly(
              ts.checkpoint().get("schemaVersion").asInt(), ts.checkpoint().get("value"));
    }

    @Test
    @DisplayName("중간 체크포인트는 재개 근거(activeChannel의 historyCursor)를 가진다")
    void midCheckpointHasActiveChannel() {
      // when
      Checkpoint first = slack.collect().firstCheckpoint();

      // then
      assertThat(
              JSON.readTree(first.valueJson()).get("activeChannel").get("historyCursor").asString())
          .isEqualTo("page:1");
    }

    @Test
    @DisplayName("재시도 대기는 없다")
    void noRetries() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("1페이지 뒤 체크포인트에서 이어 수집하면")
  class FromMidCheckpoint {
    Checkpoint mid;

    @BeforeEach
    void givenFirstCollection() {
      mid = slack.givenCollected().firstCheckpoint();
    }

    @Test
    @DisplayName("나머지 페이지만 걷는다")
    void resumesFromMidCheckpoint() {
      // when
      Collection resumed = slack.collectFrom(mid);

      // then: 기대 페이지 수는 TS 전체 수집(fixture)에서 온다 — 같은 테스트의 Java 첫 수집에서 계산하지 않는다
      assertThat(resumed.pages()).hasSize(ts.pagesApplied() - 1);
    }

    @Test
    @DisplayName("최종 체크포인트는 TS 전체 수집의 체크포인트와 같다")
    void resumedFinalCheckpointMatchesFull() {
      // when
      Collection resumed = slack.collectFrom(mid);

      // then
      assertThat(JSON.readTree(resumed.finalCheckpoint().valueJson()))
          .isEqualTo(ts.checkpoint().get("value"));
    }
  }

  @Nested
  @DisplayName("완료된 체크포인트에서 다시 수집하면")
  class FromCompletedCheckpoint {
    Checkpoint completed;
    int requestsBefore;

    @BeforeEach
    void givenFirstCollection() {
      completed = slack.givenCollected().finalCheckpoint();
      requestsBefore = slack.requests().size();
    }

    @Test
    @DisplayName("워터마크 이후만 조회해 항목이 없다")
    void completedCheckpointYieldsNothing() {
      // when
      Collection again = slack.collectFrom(completed);

      // then
      assertThat(again.mutations()).isZero();
    }

    @Test
    @DisplayName("채널당 history를 그 채널 워터마크부터 한 페이지만 요청한다")
    void completedCheckpointRequestsOnePagePerChannel() {
      // when
      slack.collectFrom(completed);

      // then
      assertThat(slack.historyRequestsSince(requestsBefore))
          .containsExactly(
              "/api/conversations.history?channel=C0000000001&oldest=1760400300.000000",
              "/api/conversations.history?channel=C0000000002&oldest=1760486420.000000");
    }
  }

  @Nested
  @DisplayName("snapshot 모드면")
  class SnapshotMode {
    @Test
    @DisplayName("완료된 체크포인트가 있어도 처음부터 걷는다")
    void snapshotIgnoresCheckpoint() {
      // given
      Checkpoint completed = slack.givenCollected().finalCheckpoint();

      // when
      Collection snapshot = slack.snapshotFrom(completed);

      // then
      assertThat(snapshot.pages()).hasSize(ts.pagesApplied());
    }

    @Test
    @DisplayName("coverage는 complete_snapshot이다")
    void snapshotCoverage() {
      // when
      CollectionCoverage coverage = slack.coverage(CollectionMode.SNAPSHOT);

      // then
      assertThat(coverage).isEqualTo(CollectionCoverage.COMPLETE_SNAPSHOT);
    }
  }

  @Nested
  @DisplayName("형식이 틀린 저장 체크포인트에서 수집하면")
  class MalformedCheckpoint {
    static final Checkpoint MALFORMED =
        new Checkpoint(2, "{\"channelWatermarks\":{\"C1\":\"bad\"}}");

    @Test
    @DisplayName("invalid_checkpoint로 실패한다")
    void malformedCheckpointIsRejected() {
      // when
      Collection collection = slack.collectFrom(MALFORMED);

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_CHECKPOINT);
    }

    @Test
    @DisplayName("요청을 보내지 않는다")
    void malformedCheckpointSendsNothing() {
      // when
      slack.collectFrom(MALFORMED);

      // then
      assertThat(slack.requests()).isEmpty();
    }
  }

  @Nested
  @DisplayName("토큰이 틀려 invalid_auth로 답하면")
  class InvalidAuth {
    @BeforeEach
    void givenWrongToken() {
      slack.withWrongToken();
    }

    @Test
    @DisplayName("authentication_failed로 끝난다")
    void invalidAuthFails() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("재시도하지 않는다(요청 1회)")
    void invalidAuthIsNotRetried() {
      // when
      slack.collect();

      // then
      assertThat(slack.requests()).hasSize(1);
    }

    @Test
    @DisplayName("재시도 대기 없이 끝난다")
    void invalidAuthDoesNotWait() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("history 첫 요청이 ratelimited(Retry-After 7)면")
  class RateLimitedOnce {
    @BeforeEach
    void givenRateLimitedOnce() {
      slack.failNext(HISTORY, 1, FakeSlackServer.Fault.rateLimited(RETRY_AFTER_SECONDS));
    }

    @Test
    @DisplayName("backoff가 아니라 Retry-After만큼 기다려 재시도한다")
    void rateLimitedWaitsRetryAfter() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).containsExactly(RETRY_AFTER);
    }

    @Test
    @DisplayName("재시도가 성공하면 수집이 끝난다")
    void rateLimitedRetriesAfterHeader() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.pages()).hasSize(ts.pagesApplied());
    }
  }

  @Nested
  @DisplayName("replies가 재시도 상한(5회) 내내 500이면")
  class ServerErrorsUntilGivingUp {
    @BeforeEach
    void givenServerErrorsUntilGivingUp() {
      slack.failNext(REPLIES, 5, FakeSlackServer.Fault.serverError(500));
    }

    @Test
    @DisplayName("dependency_unavailable로 실패한다")
    void serverErrorsExhaustRetries() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("재시도 상한까지 기다린다")
    void serverErrorsWaitUntilRetryLimit() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).hasSize(WAITS_BEFORE_GIVING_UP);
    }
  }

  @Nested
  @DisplayName("history 첫 요청이 비-JSON 본문(502 HTML)이면")
  class NonJsonBodyOnce {
    @BeforeEach
    void givenNonJsonBodyOnce() {
      slack.failNext(HISTORY, 1, FakeSlackServer.Fault.http(502, "<html>Bad Gateway</html>"));
    }

    @Test
    @DisplayName("첫 backoff만큼 기다려 재시도한다")
    void nonJsonBodyIsRetried() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).containsExactly(FIRST_RETRY_DELAY);
    }

    @Test
    @DisplayName("재시도가 성공하면 수집이 끝난다")
    void nonJsonBodyThenSuccessCompletes() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.pages()).hasSize(ts.pagesApplied());
    }
  }

  @Nested
  @DisplayName("users.conversations가 재시도 상한(5회) 내내 빈 본문 503이면")
  class EmptyBodiesUntilGivingUp {
    @BeforeEach
    void givenEmptyBodiesUntilGivingUp() {
      slack.failNext(USERS_CONVERSATIONS, 5, FakeSlackServer.Fault.http(503, ""));
    }

    @Test
    @DisplayName("dependency_unavailable로 실패한다")
    void emptyBodyIsRetriedUntilBudget() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("재시도 상한까지 기다린다")
    void emptyBodyWaitsUntilRetryLimit() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).hasSize(WAITS_BEFORE_GIVING_UP);
    }
  }

  @Nested
  @DisplayName("수집 전에 설정을 검증하면")
  class ValidateConfig {
    @Test
    @DisplayName("DM 유형(im)은 channelIds 없이는 invalid_configuration이다")
    void dmWithoutChannelIdsIsInvalid() {
      // when
      Optional<Kind> problem = slack.validate("{\"conversationTypes\":[\"im\"]}");

      // then
      assertThat(problem).contains(Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("DM 유형(im)도 channelIds가 있으면 통과한다")
    void dmWithChannelIdsIsValid() {
      // when
      Optional<Kind> problem =
          slack.validate("{\"channelIds\":[\"C1\"],\"conversationTypes\":[\"im\"]}");

      // then
      assertThat(problem).isEmpty();
    }

    @Test
    @DisplayName("historySince가 ISO UTC 형식이 아니면 invalid_configuration이다")
    void nonIsoHistorySinceIsInvalid() {
      // when
      Optional<Kind> problem = slack.validate("{\"historySince\":\"2026-01-01\"}");

      // then
      assertThat(problem).contains(Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("historySince가 정규 ISO UTC면 통과한다")
    void isoHistorySinceIsValid() {
      // when
      Optional<Kind> problem = slack.validate("{\"historySince\":\"2026-01-01T00:00:00Z\"}");

      // then
      assertThat(problem).isEmpty();
    }
  }

  @Nested
  @DisplayName("설정된 채널이 참여 목록에 없는 설정으로 수집하면")
  class UnjoinedChannel {
    Collection collectWithUnjoinedChannel() {
      return slack.collectWithConfig(
          "{\"conversationTypes\":[\"public_channel\"],\"channelIds\":[\"C0000000001\",\"CMISSING\"]}");
    }

    @Test
    @DisplayName("invalid_configuration으로 실패한다")
    void unjoinedChannelIsConfigurationError() {
      // when
      Collection collection = collectWithUnjoinedChannel();

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("실패 메시지는 빠진 채널 id를 밝힌다")
    void failureNamesMissingChannel() {
      // when
      Collection collection = collectWithUnjoinedChannel();

      // then
      assertThat(collection.failureMessage())
          .hasValueSatisfying(m -> assertThat(m).contains("CMISSING"));
    }

    @Test
    @DisplayName("참여 목록 외에는 요청하지 않는다")
    void collectsNothing() {
      // when
      collectWithUnjoinedChannel();

      // then: 요청이 0개여도 allMatch는 참이므로 참여 목록을 요청했다는 것부터 본다
      assertThat(slack.requests()).isNotEmpty().allMatch(r -> r.startsWith(USERS_CONVERSATIONS));
    }
  }

  @Nested
  @DisplayName("history를 걸은 뒤 스레드가 사라져 replies가 thread_not_found면")
  class ThreadGone {
    @BeforeEach
    void givenThreadGone() {
      slack.withThreadGone(FIRST_CHANNEL, THREAD_TS);
    }

    @Test
    @DisplayName("invalid_provider_response로 실패한다(TS classifySlackError)")
    void failsAsInvalidProviderResponse() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    @DisplayName("재시도하지 않는다(replies 요청 1회, 대기 없음)")
    void isNotRetried() {
      // when
      slack.collect();

      // then
      assertThat(slack.requests().stream().filter(r -> r.startsWith(REPLIES)))
          .as("replies 요청")
          .hasSize(1);
      assertThat(slack.waits()).isEmpty();
    }

    @Test
    @DisplayName("스레드 앞의 history 첫 페이지만 낸다")
    void keepsFirstHistoryPage() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.pages()).hasSize(1);
    }

    @Test
    @DisplayName("마지막 체크포인트는 그 스레드와 다음 history 페이지를 다시 걸을 근거다")
    void checkpointKeepsPendingThread() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(JSON.readTree(collection.finalCheckpoint().valueJson()))
          .isEqualTo(
              JSON.readTree(
                  """
                  {"channelWatermarks":{},
                   "activeChannel":{"channelId":"C0000000001","historyCursor":"page:1","latestSeen":"1760400300.000000",
                     "pendingThreads":[{"threadTs":"1760400100.000000"}]}}
                  """));
    }
  }

  @Nested
  @DisplayName("참여 목록을 받은 뒤 둘째 채널이 사라져 history가 channel_not_found면")
  class ChannelGone {
    @BeforeEach
    void givenChannelGone() {
      slack.withChannelGone(SECOND_CHANNEL);
    }

    @Test
    @DisplayName("dependency_unavailable로 실패한다(TS classifySlackError의 기본 분류)")
    void failsAsDependencyUnavailable() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("재시도 가능 실패라 재시도 상한까지 기다린다")
    void retriesUntilRetryLimit() {
      // when
      slack.collect();

      // then
      assertThat(slack.waits()).hasSize(WAITS_BEFORE_GIVING_UP);
    }

    @Test
    @DisplayName("첫 채널의 네 페이지는 낸다")
    void keepsFirstChannelPages() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.pages()).hasSize(4);
    }

    @Test
    @DisplayName("마지막 체크포인트는 첫 채널 워터마크만 가진다")
    void checkpointHasFirstChannelWatermarkOnly() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(JSON.readTree(collection.finalCheckpoint().valueJson()))
          .isEqualTo(
              JSON.readTree("{\"channelWatermarks\":{\"C0000000001\":\"1760400300.000000\"}}"));
    }
  }
}
