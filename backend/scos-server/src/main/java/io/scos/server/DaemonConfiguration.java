package io.scos.server;

import com.zaxxer.hikari.HikariDataSource;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.GracePeriod;
import io.scos.collection.internal.Maintenance;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceStartupCheck;
import io.scos.collection.internal.SourceSupervisor;
import io.scos.collection.internal.SweepMode;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.collection.internal.projection.Projectors;
import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hibernate.validator.constraints.time.DurationMin;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * `daemon` 명령의 조립(ADR-0020): 감독 설정, worker 실행기(코어 = 최대 = concurrency, 큐 없음 — 감독이 직접 분배), 감독, 데몬.
 * 프로필 daemon에서만.
 */
@Configuration(proxyBeanMethods = false)
@Profile("daemon")
@EnableConfigurationProperties(DaemonConfiguration.DaemonProperties.class)
public class DaemonConfiguration {
  /**
   * 잘못된 값은 바인딩 단계에서 기동 거부(ADR-0009): 각 값의 하한과 retryBase ≤ retryMax(교차 필드). 스냅샷 간격은 없으면 스냅샷 모드를 쓰지
   * 않는다.
   */
  @Validated
  @ConfigurationProperties("scos.daemon")
  public record DaemonProperties(
      @DefaultValue("900s") @DurationMin(seconds = 1) Duration interval,
      @DefaultValue("5s") @DurationMin(millis = 100) Duration poll,
      @DefaultValue("30s") @DurationMin(seconds = 1) Duration retryBase,
      @DefaultValue("300s") @DurationMin(seconds = 1) Duration retryMax,
      @DefaultValue("2") @Min(1) int concurrency,
      @DurationMin(seconds = 1) Duration snapshotInterval,
      @DefaultValue("10s") @DurationMin(seconds = 1) Duration shutdownTimeout,
      @DefaultValue("24h") @DurationMin(seconds = 1) Duration maintenanceInterval,
      @DefaultValue(GracePeriod.DEFAULT_DAYS) @Min(0) int maintenanceGraceDays,
      @DefaultValue("30m") @DurationMin(seconds = 1) Duration stallAfter) {
    /**
     * worker마다 페이지 커밋 1에 더해 동시에 쓸 수 있는 연결: heartbeat 스케줄러 1, 루프(투영·유지보수) 1, health 확인(db 구성요소) 1,
     * 지표 스크레이프(게이지가 Outbox 요약·데이터 나이를 읽음) 1.
     */
    static final int CONNECTIONS_BESIDES_WORKERS = 4;

    /**
     * DB 풀이 동시 작업을 감당하는가 — 아니면 기동 거부(작은 풀에서 storage_failed가 반복되는 대신). 기본 concurrency 2 + 4 = 풀 기본
     * 6.
     */
    public void requirePoolFor(int maximumPoolSize) {
      int needed = concurrency + CONNECTIONS_BESIDES_WORKERS;
      if (maximumPoolSize < needed)
        throw new IllegalStateException(
            "spring.datasource.hikari.maximum-pool-size ("
                + maximumPoolSize
                + ") must be >= scos.daemon.concurrency + "
                + CONNECTIONS_BESIDES_WORKERS
                + " ("
                + needed
                + ")");
    }

    /** 재시도 창(교차 필드). 빈 값(null)은 이 검사가 아니라 SourceSupervisor.Options가 거부한다. */
    @AssertTrue(message = "retry-base must be <= retry-max")
    public boolean isRetryBaseAtMostRetryMax() {
      return retryBase == null || retryMax == null || retryBase.compareTo(retryMax) <= 0;
    }
  }

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService collectionWorkers(DaemonProperties props) {
    AtomicInteger n = new AtomicInteger();
    ThreadFactory factory =
        r -> {
          Thread t = new Thread(r, "collection-worker-" + n.incrementAndGet());
          t.setDaemon(false);
          return t;
        };
    return Executors.newFixedThreadPool(props.concurrency(), factory);
  }

  /**
   * 기동 검증: DB 풀이 worker 수를 감당하는가(ADR-0020 §4). DataSource가 프록시로 감싸져 있어도 Hikari 풀을 찾아 본다 — 찾지 못하면 검증
   * 없이 넘어가지 않고 기동을 거부한다.
   */
  @Bean
  SmartInitializingSingleton daemonPoolSizeCheck(DaemonProperties props, DataSource dataSource) {
    return () -> props.requirePoolFor(hikariPool(dataSource).getMaximumPoolSize());
  }

  static HikariDataSource hikariPool(DataSource dataSource) {
    try {
      if (dataSource.isWrapperFor(HikariDataSource.class))
        return dataSource.unwrap(HikariDataSource.class);
    } catch (SQLException e) {
      throw new IllegalStateException("cannot inspect the DataSource pool", e);
    }
    throw new IllegalStateException(
        "daemon requires a HikariCP DataSource to check maximum-pool-size, got "
            + dataSource.getClass().getName());
  }

  /**
   * 기동 검증(ADR-0022 §5): 켜진 소스마다 어댑터·설정·자격 증명을 확인하고, 하나라도 틀리면 컨텍스트가 뜨지 않는다(종료 코드 1). 잘못 옮긴 등록값이
   * health 녹색 뒤의 run 실패로만 드러나지 않게 한다.
   */
  @Bean
  SmartInitializingSingleton daemonSourceStartupCheck(
      SourceInstanceStore sources, SourceAdapters adapters, CredentialResolver credentials) {
    return () -> new SourceStartupCheck(sources, adapters, credentials).verify();
  }

  /**
   * 기동 검사(stage2 §3.3): 투영이 쓰는 공간 가운데 하나라도 저장된 임베딩 세대가 설정 임베더와 다르거나 재임베딩이 끝나지 않았으면 종료 코드 1. 투영 실패가
   * 반복되는 데몬이 health 녹색으로 뜨는 대신 {@code scos reembed}를 안내한다.
   */
  @Bean
  SmartInitializingSingleton daemonEmbeddingGenerationCheck(
      SpaceMeta meta, ProjectionSpaces spaces, Embedder embedder) {
    return () ->
        EmbeddingGenerationCheck.verify(
            meta, spaces.spaces(), EmbeddingGenerationCheck.generationOf(embedder));
  }

  /**
   * 수집 어댑터가 있는 kind는 투영기도 있어야 한다(ADR-0022 §5). 없으면 수집은 되는데 투영 요청이 SKIPPED_UNKNOWN_KIND로 소모돼, 나중에
   * 투영기를 넣어도 다음 변경 전까지 투영되지 않는다.
   */
  @Bean
  SmartInitializingSingleton daemonProjectorCoverageCheck(
      SourceAdapters adapters, Projectors projectors) {
    return () -> projectors.requireCovering(adapters.kinds());
  }

  @Bean
  SourceSupervisor sourceSupervisor(
      SourceInstanceStore sources,
      RunStore runs,
      WorkClaimPort claims,
      CollectionRuntime runtime,
      DaemonProperties props,
      Clock clock,
      ExecutorService collectionWorkers) {
    return new SourceSupervisor(
        sources,
        runs,
        claims,
        runtime::collect,
        new SourceSupervisor.Options(
            props.interval(),
            props.retryBase(),
            props.retryMax(),
            props.concurrency(),
            Optional.ofNullable(props.snapshotInterval())),
        clock,
        collectionWorkers);
  }

  @Bean
  CollectionDaemon collectionDaemon(
      SourceSupervisor supervisor,
      ProjectionRun projection,
      DaemonProperties props,
      ExecutorService collectionWorkers,
      Clock clock,
      DaemonObserver observer,
      Maintenance maintenance,
      Progress progress) {
    return new CollectionDaemon(
        supervisor,
        projection,
        new DaemonMaintenance(
            () ->
                maintenance.run(
                    GracePeriod.ofDays(props.maintenanceGraceDays()), SweepMode.DELETE)),
        new CollectionDaemon.Options(
            props.poll(), props.maintenanceInterval(), props.shutdownTimeout()),
        collectionWorkers,
        clock,
        progress,
        observer::record);
  }

  /** 서버 namespace로 MCP·서버와 관리 포트를 구별한다(포트 0의 실제 포트도 운영 로그로 알린다). */
  @Bean
  ApplicationListener<WebServerInitializedEvent> daemonHttpPortLogger(
      ManagementServerProperties management) {
    return event -> {
      var logger = LoggerFactory.getLogger(DaemonConfiguration.class);
      int port = event.getWebServer().getPort();
      if ("management".equals(event.getApplicationContext().getServerNamespace())) {
        // daemon 프로필은 관리 주소를 명시한다(application-daemon.yml).
        var address = Objects.requireNonNull(management.getAddress());
        logger.info("daemon management port={} address={}", port, address.getHostAddress());
      } else logger.info("daemon http port={}", port);
    };
  }

  /** 빈 이름 daemonReadinessHealthIndicator → readiness 그룹의 구성요소 `daemonReadiness`. */
  @Bean
  DaemonReadinessIndicator daemonReadinessHealthIndicator(DaemonObserver observer) {
    return new DaemonReadinessIndicator(() -> observer.last().isPresent());
  }

  /** 빈 이름 daemonHealthIndicator → health 구성요소 `daemon`. */
  @Bean
  DaemonHealthIndicator daemonHealthIndicator(
      ObjectProvider<CollectionDaemon> daemon,
      DaemonObserver observer,
      Clock clock,
      DaemonProperties props) {
    return new DaemonHealthIndicator(
        () -> DaemonHealthIndicator.DaemonState.of(daemon.getObject(), observer.last()),
        clock,
        props.stallAfter());
  }

  /** 투영 요청·연속 실패·데이터 나이 게이지(Actuator가 MeterBinder 빈을 앱 레지스트리에 묶는다). */
  @Bean
  DaemonGauges daemonGauges(
      OutboxPort outbox, UnitMaintenance units, Clock clock, ProjectionSpaces spaces) {
    return new DaemonGauges(outbox, new FreshnessReader(units, clock), spaces.spaces(), clock);
  }

  @Bean
  DaemonObserver daemonObserver(CollectionMetrics metrics) {
    return new DaemonObserver(metrics.registry());
  }
}
