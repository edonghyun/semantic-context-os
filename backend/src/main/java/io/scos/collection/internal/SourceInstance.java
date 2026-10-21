package io.scos.collection.internal;

import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;

/**
 * 등록된 소스(TS SourceInstance). config는 어댑터 소유 JSON(schemaVersion + value). credentialRef는
 * `<scheme>:<참조>`(ADR-0022 §2). schedule은 소스별 감독 주기(§3). shrinkGuard는 소스별 스냅샷 축소 보호 기준(ADR-0028).
 */
public record SourceInstance(
    SourceInstanceId id,
    String kind,
    String displayName,
    SourceConfig config,
    String credentialRef,
    boolean enabled,
    SourceSchedule schedule,
    SnapshotShrinkOverrides shrinkGuard,
    Instant createdAt,
    Instant updatedAt) {
  public SourceInstance {
    if (id == null) throw new IllegalArgumentException("id");
    if (kind == null || kind.isBlank())
      throw new IllegalArgumentException("kind must not be blank");
    if (config == null) throw new IllegalArgumentException("config");
    if (credentialRef == null || credentialRef.isBlank())
      throw new IllegalArgumentException("credentialRef must not be blank");
    if (schedule == null) throw new IllegalArgumentException("schedule");
    if (shrinkGuard == null) throw new IllegalArgumentException("shrinkGuard");
    displayName = displayName == null ? "" : displayName;
    if (createdAt == null || updatedAt == null) throw new IllegalArgumentException("timestamps");
  }

  /** 커넥터 입력으로 넘길 다섯 필드. */
  public SourceDescriptor descriptor() {
    return new SourceDescriptor(id, kind, displayName, config, credentialRef);
  }

  /** 소스별 축소 보호 기준 없이(데몬 기본값) — 등록 이전부터 있던 호출 모양. */
  public SourceInstance(
      SourceInstanceId id,
      String kind,
      String displayName,
      SourceConfig config,
      String credentialRef,
      boolean enabled,
      SourceSchedule schedule,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        kind,
        displayName,
        config,
        credentialRef,
        enabled,
        schedule,
        SnapshotShrinkOverrides.DAEMON_DEFAULTS,
        createdAt,
        updatedAt);
  }
}
