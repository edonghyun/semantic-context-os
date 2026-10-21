package io.scos.collection.internal;

import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Clock;
import java.util.regex.Pattern;

/**
 * 소스 등록(TS SourceRegistrar): 어댑터가 설정을 검증한 뒤 저장한다. 다시 등록하면 주기를 포함해 전부 새 값으로 바뀐다. credentialRef는 풀지
 * 않지만(TS와 같음 — 풀어 보는 것은 데몬 기동 검증, ADR-0022 §5) 스킴 접두사 모양은 본다: 접두사 없는 ref는 해석에서 거부되므로(§2) 등록부터 받지
 * 않는다. 오류에는 ref를 싣지 않는다(토큰을 붙여 넣었을 수 있다).
 */
public final class SourceRegistrar {
  private static final Pattern PREFIXED_REF =
      Pattern.compile("^[a-z][a-z0-9-]*:.+$", Pattern.DOTALL);

  private final SourceInstanceStore sources;
  private final SourceAdapters adapters;
  private final Clock clock;

  public SourceRegistrar(SourceInstanceStore sources, SourceAdapters adapters, Clock clock) {
    this.sources = sources;
    this.adapters = adapters;
    this.clock = clock;
  }

  public SourceInstance register(
      SourceInstanceId id,
      String kind,
      String displayName,
      SourceConfig config,
      String credentialRef,
      SourceSchedule schedule) {
    return register(
        id,
        kind,
        displayName,
        config,
        credentialRef,
        schedule,
        SnapshotShrinkOverrides.DAEMON_DEFAULTS);
  }

  /** 소스별 스냅샷 축소 보호 기준까지(ADR-0028). 다시 등록하면 기준도 새 값으로 바뀐다. */
  public SourceInstance register(
      SourceInstanceId id,
      String kind,
      String displayName,
      SourceConfig config,
      String credentialRef,
      SourceSchedule schedule,
      SnapshotShrinkOverrides shrinkGuard) {
    adapters.require(kind).validateConfig(config);
    if (!PREFIXED_REF.matcher(credentialRef).matches())
      throw new IllegalArgumentException(
          "credentialRef needs a scheme prefix: <scheme>:<reference> (see ADR-0022 §2)");
    var now = clock.instant();
    SourceInstance instance =
        new SourceInstance(
            id,
            kind,
            displayName,
            config,
            credentialRef,
            true,
            schedule,
            shrinkGuard,
            sources.get(id).map(SourceInstance::createdAt).orElse(now),
            now);
    sources.save(instance);
    return instance;
  }

  /**
   * 소스를 켜거나 끈다(F9 재수집 절차 — 운영 전환 전 Java 데몬이 그 소스를 감독하지 않게). enabled와 수정 시각만 바꾼다. 꺼진 소스는 데몬이 감독하지 않고
   * {@code collect}가 거부한다. 다시 {@link #register}하면 켜진다(TS와 같음). TS CLI에는 없는 Java 운영 명령이다.
   */
  public SourceInstance setEnabled(SourceInstanceId id, boolean enabled) {
    SourceInstance current =
        sources
            .get(id)
            .orElseThrow(
                () -> new IllegalArgumentException("Source instance not found: " + id.value()));
    SourceInstance changed =
        new SourceInstance(
            current.id(),
            current.kind(),
            current.displayName(),
            current.config(),
            current.credentialRef(),
            enabled,
            current.schedule(),
            current.createdAt(),
            clock.instant());
    sources.save(changed);
    return changed;
  }
}
