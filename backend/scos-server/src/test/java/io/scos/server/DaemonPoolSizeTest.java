package io.scos.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** DB 풀은 worker 수 + 4(heartbeat·루프·health·지표 스크레이프) 이상이어야 데몬이 기동한다(ADR-0020 §4). */
@DisplayName("데몬은 DB 풀이 worker 수 + 4 이상일 때만 기동한다(ADR-0020 §4)")
class DaemonPoolSizeTest {
  /** 기본값(2)과 다른 worker 수 — 필요한 풀 크기가 설정한 concurrency에 달린 것이 보이게. */
  static final int CONCURRENCY = 3;

  static final int ENOUGH = CONCURRENCY + 4;

  /** concurrency만 정하고 나머지는 운영과 같은 바인딩 기본값으로 둔 데몬 설정. */
  static DaemonConfiguration.DaemonProperties withConcurrency(int concurrency) {
    return new Binder(
            new MapConfigurationPropertySource(
                Map.of("scos.daemon.concurrency", String.valueOf(concurrency))))
        .bindOrCreate("scos.daemon", DaemonConfiguration.DaemonProperties.class);
  }

  @Test
  @DisplayName("풀이 worker 수 + 4이면 받아들인다")
  void acceptsEnoughConnections() {
    // when·then
    assertThatCode(() -> withConcurrency(CONCURRENCY).requirePoolFor(ENOUGH))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("풀이 worker 수 + 4보다 작으면 필요한 크기를 밝혀 기동을 거부한다")
  void rejectsTooSmallPool() {
    // when·then
    assertThatThrownBy(() -> withConcurrency(CONCURRENCY).requirePoolFor(ENOUGH - 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("maximum-pool-size (6) must be >= scos.daemon.concurrency + 4 (7)");
  }

  @Test
  @DisplayName("DataSource가 프록시로 감싸져 있어도 안의 Hikari 풀을 찾아 크기를 검사한다")
  void findsPoolBehindProxy() {
    try (HikariDataSource pool = new HikariDataSource()) {
      // given: 작은 풀이 프록시 뒤에 있다
      pool.setMaximumPoolSize(ENOUGH - 1);
      DataSource proxied = new DelegatingDataSource(pool);

      // when
      HikariDataSource found = DaemonConfiguration.hikariPool(proxied);

      // then
      assertThatThrownBy(
              () -> withConcurrency(CONCURRENCY).requirePoolFor(found.getMaximumPoolSize()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be >= scos.daemon.concurrency + 4");
    }
  }

  @Test
  @DisplayName("Hikari 풀을 찾을 수 없는 DataSource면 기동을 거부한다")
  void refusesUnknownPool() {
    // given
    DataSource unknown = new SimpleDriverDataSource();

    // when·then
    assertThatThrownBy(() -> DaemonConfiguration.hikariPool(unknown))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires a HikariCP DataSource");
  }
}
