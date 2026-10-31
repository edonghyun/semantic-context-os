package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 원천별 성공 수집 완료 시각의 공통 계약(B9). */
@DisplayName("원천 최신성은 요청한 원천의 마지막 성공 완료 시각이다(B9)")
public abstract class SourceFreshnessContract {
  protected static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  protected static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");
  protected static final Instant EARLIER = Instant.parse("2026-10-13T00:00:00Z");
  protected static final Instant LATER = Instant.parse("2026-10-13T11:00:00Z");
  protected SourceFreshness freshness;

  protected abstract SourceFreshness newFreshness();

  protected abstract void recordSuccess(SourceInstanceId instance, Instant finishedAt);

  protected abstract void recordNonSuccess(SourceInstanceId instance, Instant finishedAt);

  @BeforeEach
  void createFreshness() {
    freshness = newFreshness();
  }

  @Test
  @DisplayName("성공 run의 마지막 완료 시각을 돌려준다")
  void latestSuccessFinish() {
    // given: 기록 순서가 달라도 완료 시각의 최댓값이다
    recordSuccess(SLACK, LATER);
    recordSuccess(SLACK, EARLIER);
    recordSuccess(DRIVE, EARLIER);

    // when
    var last = freshness.lastSucceeded(Set.of(SLACK, DRIVE));

    // then
    assertThat(last).isEqualTo(Map.of(SLACK, LATER, DRIVE, EARLIER));
  }

  @Test
  @DisplayName("성공 뒤의 비성공 run은 값을 바꾸지 않는다")
  void laterNonSuccessKeepsSuccess() {
    // given
    recordSuccess(SLACK, EARLIER);
    recordNonSuccess(SLACK, LATER);

    // when
    var last = freshness.lastSucceeded(Set.of(SLACK));

    // then
    assertThat(last).isEqualTo(Map.of(SLACK, EARLIER));
  }

  @Test
  @DisplayName("요청하지 않은 인스턴스는 담지 않는다")
  void unrequestedInstancesAreOmitted() {
    // given
    recordSuccess(SLACK, EARLIER);
    recordSuccess(DRIVE, LATER);

    // when
    var last = freshness.lastSucceeded(Set.of(SLACK));

    // then
    assertThat(last).isEqualTo(Map.of(SLACK, EARLIER));
  }

  @Test
  @DisplayName("성공이 없는 인스턴스는 담지 않는다")
  void instancesWithoutSuccessAreOmitted() {
    // given
    recordSuccess(SLACK, EARLIER);
    recordNonSuccess(DRIVE, LATER);

    // when
    var last = freshness.lastSucceeded(Set.of(SLACK, DRIVE));

    // then
    assertThat(last).isEqualTo(Map.of(SLACK, EARLIER));
  }

  @Test
  @DisplayName("요청 인스턴스가 비면 빈 맵이다")
  void emptyRequestIsEmpty() {
    // given
    recordSuccess(SLACK, EARLIER);

    // when
    var last = freshness.lastSucceeded(Set.of());

    // then
    assertThat(last).isEmpty();
  }
}
