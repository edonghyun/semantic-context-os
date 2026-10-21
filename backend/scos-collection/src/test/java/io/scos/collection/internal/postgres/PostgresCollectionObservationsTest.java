package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.RawItems;
import io.scos.collection.internal.RunId;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DB 관찰 어휘 자체 테스트(F15 C7): 커넥터 DB 종단·kill 테스트의 then이 기대는 관찰이 틀린 상태에서 제대로 답하거나 given 준비 실패로 멈추는지 본다.
 * 관찰이 틀리면 "tombstone 없음"·"점유 없음" 같은 부재 단언이 거짓으로 통과한다.
 */
@DisplayName("DB 관찰 어휘는 틀린 상태에서 바로 답하거나 멈춘다(C7)")
@Tag("db")
class PostgresCollectionObservationsTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("obs-src");
  static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
  static final Duration TTL = Duration.ofSeconds(30);
  static final Duration UNTIL_ONE_SECOND_BEFORE_EXPIRY = Duration.ofSeconds(29);

  final MutableClock clock = new MutableClock(T0);
  final PostgresCollectionConfiguration.Adapters adapters =
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  final PostgresCollectionObservations observe =
      new PostgresCollectionObservations(PostgresSupport.dataSource(), SOURCE, clock);

  @BeforeEach
  void clean() {
    PostgresSupport.truncateAll();
  }

  @Nested
  @DisplayName("점유 행이 없으면")
  class NoClaim {
    @Test
    @DisplayName("holdsActiveClaim은 given 준비 실패로 멈춘다")
    void holdsActiveClaimStops() {
      // when·then
      assertThatThrownBy(observe::holdsActiveClaim)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("given 준비 실패: 소스 obs-src의 점유 행이 없다");
    }

    @Test
    @DisplayName("claimExpiresAt은 given 준비 실패로 멈춘다")
    void claimExpiresAtStops() {
      // when·then
      assertThatThrownBy(observe::claimExpiresAt)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("given 준비 실패: 소스 obs-src의 점유 행이 없다");
    }

    @Test
    @DisplayName("leaseExpired는 참이다")
    void leaseIsExpired() {
      // when
      boolean expired = observe.leaseExpired();

      // then
      assertThat(expired).isTrue();
    }
  }

  @Nested
  @DisplayName("T0에 TTL 30초로 점유했으면")
  class Claimed {
    @BeforeEach
    void givenClaimed() {
      if (adapters.claims().tryClaim(SOURCE, new OwnerId("w1"), TTL, T0).isEmpty())
        throw new IllegalStateException("given 준비 실패: 빈 저장소에서 점유하지 못했다");
    }

    @Test
    @DisplayName("만료 1초 전에는 살아 있다")
    void activeBeforeExpiry() {
      // given
      clock.advance(UNTIL_ONE_SECOND_BEFORE_EXPIRY);

      // when
      boolean active = observe.holdsActiveClaim();

      // then
      assertThat(active).isTrue();
    }

    @Test
    @DisplayName("만료 정각에는 살아 있지 않다")
    void inactiveAtExpiry() {
      // given
      clock.advance(TTL);

      // when
      boolean active = observe.holdsActiveClaim();

      // then
      assertThat(active).isFalse();
    }

    @Test
    @DisplayName("만료 시각은 T0 + 30초다")
    void expiresAtTtl() {
      // when
      Instant expiresAt = observe.claimExpiresAt();

      // then
      assertThat(expiresAt).isEqualTo(Instant.parse("2026-10-16T00:00:30Z"));
    }
  }

  @Test
  @DisplayName("없는 run을 읽으면 given 준비 실패로 멈춘다")
  void missingRunStops() {
    // when·then
    assertThatThrownBy(() -> observe.run(new RunId("no-such-run")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("given 준비 실패: run no-such-run이 없다");
  }

  @Test
  @DisplayName("같은 시각에 시작한 run은 run id 순으로 나열한다")
  void runStatusesTieBreakOnRunId() {
    // given: 나중 id를 먼저 넣는다
    JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
    for (String[] row : new String[][] {{"run-b", "failed", "x"}, {"run-a", "succeeded", null}})
      jdbc.sql(
              "INSERT INTO collection_runs (run_id, source_instance_id, status, started_at, error_kind) VALUES (:id, :s, :status, :at, :kind)")
          .param("id", row[0])
          .param("s", SOURCE.value())
          .param("status", row[1])
          .param("at", Timestamp.from(T0))
          .param("kind", row[2])
          .update();

    // when
    var statuses = observe.runStatuses();

    // then
    assertThat(statuses).containsExactly("succeeded/-", "failed/x");
  }

  @Test
  @DisplayName("active 원본이 한 페이지(100)를 넘어도 모두 읽는다")
  void activeVersionsReadsEveryPage() {
    // given
    for (int i = 0; i < 101; i++)
      adapters
          .rawItems()
          .apply(SOURCE, RawItems.item(String.format("item-%03d", i)).inlineJson("{}").upsert());

    // when
    Map<String, String> active = observe.activeVersions();

    // then
    assertThat(active).hasSize(101).containsEntry("item-100", "v1");
  }
}
