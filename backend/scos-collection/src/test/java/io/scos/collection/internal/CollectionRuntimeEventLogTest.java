package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.LogCapture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 런타임의 운영 이벤트 로그(TS collection-runtime.ts의 logger.log — 데몬 JSON 로그): 거부 항목마다 warn
 * `collection.item.rejected`, 실패한 run은 error `collection.run.failed`, 취소된 run은 info
 * `collection.run.cancelled`. detail은 TS와 같은 필드(status·errorKind·errorSummary /
 * failureKind·message·providerLocator)다.
 */
@DisplayName("수집 런타임 운영 이벤트 — 거부·실패·취소를 TS와 같은 줄로 남긴다")
// 제품 클래스의 공용 로거에 appender를 단다 — 같은 JVM의 다른 테스트가 남긴 줄이 섞이지 않게 혼자 돈다(TESTING §6.3)
@Isolated
class CollectionRuntimeEventLogTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");

  /** 이 테스트의 run id — 조립 기본값(run-1)에 기대지 않게 본문이 정한다. */
  static final RunId RUN = new RunId("run-events");

  static final String LOCATOR = "{\"kind\":\"components\",\"id\":\"10001\",\"start\":0}";

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
  final LogCapture log = LogCapture.of(CollectionRuntime.class);
  final CancellationToken cancel = new CancellationToken();

  @AfterEach
  void detach() {
    log.close();
  }

  void collectWith(SourceAdapter adapter) {
    MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
    sources.save(SourceInstances.noop(SOURCE, clock.instant()));
    MemoryCollectionRuntime.over(clock, sources, new MemoryWorkClaimPort(), new MemoryRunStore())
        .runIds(() -> RUN)
        .adapter(adapter)
        .build()
        .collect(SOURCE, CollectionMode.INCREMENTAL, cancel);
  }

  static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  /** 주어진 거부 하나만 담은 페이지를 내는 어댑터. */
  static SourceAdapter rejecting(RejectedItem rejection) {
    return SourceInstances.adapter(
        input -> {},
        sink ->
            sink.accept(
                new CollectionPage(List.of(), List.of(rejection), new Checkpoint(1, "{}"))));
  }

  /** 수집 준비에서 공급자 호출 실패를 던지는 어댑터. */
  static SourceAdapter failingWith(CallFailure.Kind kind, String message) {
    return SourceInstances.adapter(
        input -> {
          throw new CallFailureException("jira.read", CallFailure.of(kind, message));
        });
  }

  /** 수집 중 취소 토큰을 당기고 cancelled 실패를 던지는 어댑터. */
  SourceAdapter cancellingDuringCollection() {
    return SourceInstances.adapter(
        input -> {
          cancel.cancel();
          throw new CallFailureException(
              "jira.read", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
        });
  }

  @Test
  @DisplayName("거부 항목마다 warn collection.item.rejected를 소스·run·거부 내용과 함께 남긴다")
  void logsRejection() {
    // given
    RejectedItem rejection =
        new RejectedItem(
            RejectedItem.FailureKind.UNSUPPORTED_ITEM,
            "Jira resource unavailable",
            Optional.of(LOCATOR),
            clock.instant(),
            Optional.empty());

    // when
    collectWith(rejecting(rejection));

    // then
    assertThat(log.events("collection.item.rejected"))
        .singleElement()
        .isEqualTo(
            json(
                "{\"level\":\"warn\",\"event\":\"collection.item.rejected\",\"sourceInstanceId\":\"src-a\","
                    + "\"runId\":\"run-events\",\"detail\":{\"failureKind\":\"unsupported_item\","
                    + "\"message\":\"Jira resource unavailable\",\"providerLocator\":"
                    + LOCATOR
                    + "}}"));
  }

  @Test
  @DisplayName("실패한 run은 error collection.run.failed를 상태·실패 종류·요약과 함께 남긴다")
  void logsFailedRun() {
    // when
    collectWith(failingWith(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "HTTP 503"));

    // then
    assertThat(log.events("collection.run.failed"))
        .singleElement()
        .isEqualTo(
            json(
                "{\"level\":\"error\",\"event\":\"collection.run.failed\",\"sourceInstanceId\":\"src-a\","
                    + "\"runId\":\"run-events\",\"detail\":{\"status\":\"failed\",\"errorKind\":\"dependency_unavailable\","
                    + "\"errorSummary\":\"HTTP 503\"}}"));
  }

  @Test
  @DisplayName("취소된 run은 error가 아니라 info collection.run.cancelled로 남긴다")
  void logsCancelledRunAsInfo() {
    // when
    collectWith(cancellingDuringCollection());

    // then
    assertThat(log.events())
        .extracting(e -> e.get("event").asString() + "/" + e.get("level").asString())
        .containsExactly("collection.run.cancelled/info");
  }
}
