package io.scos.collection.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Optional;

/**
 * 수집 지표(공급자 호출 지표는 providercall.ProviderCallMetrics, ADR-0022 §4)(ADR-0009 이름 고정, ADR-0020 §3). 태그는
 * 저카디널리티만 — 소스 id·본문·질의 금지. 지표 백엔드 실패는 기능에 영향이 없어야 하므로 등록만 하고 값을 읽지 않는다.
 */
public final class CollectionMetrics {
  public static final String RUNS = "scos.collection.runs";
  public static final String CLAIM_CONTENTION = "scos.collection.claim_contention";
  public static final String PAGE_COMMIT = "scos.collection.page_commit";
  public static final String LEASE_HEARTBEAT = "scos.collection.lease_heartbeat";
  public static final String SNAPSHOT_RECONCILE_GUARDED =
      "scos.collection.snapshot_reconcile_guarded";

  /** heartbeat 한 번의 결과. */
  public enum HeartbeatOutcome {
    OK,
    FAILED;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final MeterRegistry registry;

  private CollectionMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public static CollectionMetrics of(MeterRegistry registry) {
    return new CollectionMetrics(registry);
  }

  public MeterRegistry registry() {
    return registry;
  }

  /** errorKind가 없으면(오류 없는 종료·취소) error_kind=none. */
  public void runFinished(RunStatus status, Optional<String> errorKind) {
    Counter.builder(RUNS)
        .tag("status", status.wire())
        .tag("error_kind", errorKind.orElse("none"))
        .register(registry)
        .increment();
  }

  public void claimContention() {
    Counter.builder(CLAIM_CONTENTION).register(registry).increment();
  }

  public Timer pageCommit() {
    return Timer.builder(PAGE_COMMIT).register(registry);
  }

  /** 스냅샷 축소 보호가 정리를 건너뛴 완료 하나(ADR-0028). */
  public void reconcileGuarded() {
    Counter.builder(SNAPSHOT_RECONCILE_GUARDED).register(registry).increment();
  }

  public void heartbeat(HeartbeatOutcome outcome) {
    Counter.builder(LEASE_HEARTBEAT).tag("outcome", outcome.wire()).register(registry).increment();
  }
}
