package io.scos.server;

import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * readiness 그룹의 `daemonReadiness` 구성요소(ADR-0020 §3): 첫 사이클이 끝나기 전에는 OUT_OF_SERVICE(HTTP 503), 뒤에는
 * UP. Boot가 기동 완료 때 내는 ACCEPTING_TRAFFIC과 별개로 그룹에 포함돼 readiness를 "첫 사이클 완료"로 만든다
 * (application-daemon.yml `management.endpoint.health.group.readiness.include`).
 */
public final class DaemonReadinessIndicator implements HealthIndicator {
  private final BooleanSupplier firstCycleDone;

  public DaemonReadinessIndicator(BooleanSupplier firstCycleDone) {
    this.firstCycleDone = firstCycleDone;
  }

  @Override
  public Health health() {
    boolean ready = firstCycleDone.getAsBoolean();
    return (ready ? Health.up() : Health.outOfService())
        .withDetail("firstCycleDone", ready)
        .build();
  }
}
