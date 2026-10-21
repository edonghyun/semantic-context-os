package io.scos.boot;

import io.scos.collection.internal.BlobGarbageCollector;
import io.scos.collection.internal.BlobMaintenancePort;
import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.Maintenance;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.RunCompletion;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStore;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkLimits;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceChangeLog;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceRegistrar;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.collection.internal.credentials.SchemeCredentialResolver;
import io.scos.connector.slack.SlackAdapter;
import io.scos.connector.slack.internal.SlackWebApiClient;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderHttp;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceAdapter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 런타임 조립(ADR-0018 §1·§2): 공급자 호출 실행기(F7 기본 정책), 콘텐츠 캡처(F8), 환경변수 자격 증명, Slack 어댑터(RestClient),
 * heartbeat 스케줄러, 런타임(owner = 호스트:pid). 저장 포트는 PostgresCollectionConfiguration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectionConfiguration.CollectionProperties.class)
public class CollectionConfiguration {
  /** 잘못된 설정은 기동 시 Bean Validation이 거부한다(ADR-0009). 기본값은 @DefaultValue — 빈 값은 검증이 잡는다. */
  @Validated
  @ConfigurationProperties("scos.collection")
  public record CollectionProperties(
      @DefaultValue("180s") @DurationMin(seconds = 2) Duration leaseTtl,
      @DefaultValue("https://slack.com") @NotBlank @Pattern(regexp = "https?://.+")
          String slackBaseUrl,
      @DefaultValue @Valid SnapshotGuard snapshotGuard) {}

  /**
   * 스냅샷 축소 보호 데몬 기본값(ADR-0028). 소스별 값(`source add --snapshot-guard-…`)이 우선한다. acceptTtl은 `source
   * accept-shrink` 허용의 유효 기간이다.
   */
  public record SnapshotGuard(
      @DefaultValue("10") @Min(1) int minActive,
      @DefaultValue("50") @Min(1) @Max(100) int maxDeletePercent,
      @DefaultValue("7d") @DurationMin(seconds = 1) Duration acceptTtl) {
    SnapshotShrinkLimits limits() {
      return new SnapshotShrinkLimits(minActive, maxDeletePercent);
    }

    /** 이 설정(기준·허용 유효 기간)으로 보호를 조립한다. */
    public SnapshotShrinkGuard guard(
        SourceInstanceStore sources, SnapshotGuardStore store, CollectionMetrics metrics) {
      return new SnapshotShrinkGuard(sources, store, limits(), metrics, acceptTtl);
    }
  }

  @Bean
  SnapshotShrinkGuard snapshotShrinkGuard(
      SourceInstanceStore sources,
      SnapshotGuardStore store,
      CollectionProperties props,
      CollectionMetrics metrics) {
    return props.snapshotGuard().guard(sources, store, metrics);
  }

  @Bean
  ProviderCallExecutor providerCallExecutor(Clock clock, ProviderCallMetrics metrics) {
    return ProviderCallExecutor.defaults(clock, metrics);
  }

  @Bean
  RawContentCapture rawContentCapture() {
    return RawContentCapture.defaults();
  }

  @Bean
  CredentialResolver credentialResolver() {
    return SchemeCredentialResolver.standard(System.getenv());
  }

  @Bean
  SlackAdapter slackAdapter(
      CollectionProperties props,
      CredentialResolver credentials,
      ProviderCallExecutor calls,
      ObjectMapper json,
      Clock clock) {
    return new SlackAdapter(
        credentials,
        new SlackWebApiClient(
            props.slackBaseUrl(), ProviderHttp.of(HttpClient.newHttpClient(), calls), json),
        json,
        clock);
  }

  /** heartbeat 스케줄러(계획 4.1·ADR-0018 §1: TaskScheduler). 종료 순서는 P5 SmartLifecycle. */
  @Bean
  ThreadPoolTaskScheduler heartbeatScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("lease-heartbeat-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  /** 수집·투영이 같은 TTL·스케줄러·지표로 점유를 갱신한다. */
  @Bean
  LeaseHeartbeats leaseHeartbeats(
      WorkClaimPort claims,
      CollectionProperties props,
      Clock clock,
      TaskScheduler heartbeatScheduler,
      CollectionMetrics metrics) {
    return new LeaseHeartbeats(claims, props.leaseTtl(), clock, heartbeatScheduler, metrics);
  }

  /** 작업 진행 신호(페이지 커밋·heartbeat·투영 문서 쓰기) — 데몬 health의 정체 판정이 읽는다. */
  @Bean
  Progress progress(Clock clock) {
    return new Progress(clock);
  }

  @Bean
  CollectionRuntime collectionRuntime(
      SourceInstanceStore sources,
      SourceAdapters adapters,
      RawContentCapture content,
      ProviderCallExecutor calls,
      WorkClaimPort claims,
      CheckpointPort checkpoints,
      RunStore runs,
      PageCommit pageCommit,
      RunCompletion completion,
      LeaseHeartbeats heartbeats,
      Clock clock,
      CollectionMetrics metrics) {
    OwnerId owner = owner();
    return new CollectionRuntime(
        sources,
        adapters,
        content,
        calls,
        claims,
        checkpoints,
        runs,
        pageCommit,
        completion,
        owner,
        heartbeats,
        clock,
        RunId::random,
        metrics);
  }

  /** owner = 호스트:pid — 수집·투영 점유가 같은 주체를 쓴다. */
  public static OwnerId owner() {
    String host;
    try {
      host = java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.net.UnknownHostException e) {
      host = "unknown-host";
    }
    return new OwnerId(host + ":" + ProcessHandle.current().pid());
  }

  @Bean
  SourceRegistrar sourceRegistrar(
      SourceInstanceStore sources, SourceAdapters adapters, Clock clock) {
    return new SourceRegistrar(sources, adapters, clock);
  }

  /** 등록된 kind별 수집 어댑터 전부(커넥터마다 SourceAdapter 빈 하나, ADR-0022 §5). */
  @Bean
  SourceAdapters sourceAdapters(List<SourceAdapter> all) {
    return SourceAdapters.of(all);
  }

  /** F9·F10 유지보수(명령 maintenance·데몬 간격). */
  @Bean
  Maintenance maintenance(BlobMaintenancePort blobs, SourceChangeLog changes, Clock clock) {
    return new Maintenance(new BlobGarbageCollector(blobs, clock), changes);
  }
}
