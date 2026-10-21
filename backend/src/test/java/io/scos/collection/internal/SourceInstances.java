package io.scos.collection.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 소스 준비(테스트 공용): 메모리 런타임·감독·데몬 테스트의 "noop" 소스·어댑터와, 커넥터 테스트가 쓰는 종류별 소스({@link #of}). */
public final class SourceInstances {
  public static final String NOOP_KIND = "noop";

  /** 아무것도 걷지 않는 증분 어댑터. */
  public static final SourceAdapter NOOP_ADAPTER = adapter(input -> {});

  /** strictAdapter의 kind. */
  public static final String STRICT_KIND = "strict";

  /** 설정 값이 "{}"가 아니면 invalid_configuration("strict config rejected")으로 거부하는 어댑터(수집은 하지 않는다). */
  public static SourceAdapter strictAdapter() {
    return new SourceAdapter() {
      @Override
      public String kind() {
        return STRICT_KIND;
      }

      @Override
      public void validateConfig(SourceConfig config) {
        if (!config.valueJson().equals("{}"))
          throw new CallFailureException(
              "strict.configuration",
              CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, "strict config rejected"));
      }

      @Override
      public CollectionCoverage coverage(
          SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
        return CollectionCoverage.INCREMENTAL;
      }

      @Override
      public void collect(CollectionInput input, PageSink sink) {}
    };
  }

  private SourceInstances() {}

  /** 켜진 noop 소스(설정 "{}", 자격 증명 참조 env:NOOP_TOKEN). */
  public static SourceInstance noop(SourceInstanceId id, Instant at) {
    return of(id, NOOP_KIND, "{}", "env:NOOP_TOKEN", at);
  }

  /** 켜진 소스 하나(이름은 id, 설정 스키마 1, 등록·수정 시각 at) — 커넥터 테스트가 자기 종류로 쓴다. */
  public static SourceInstance of(
      SourceInstanceId id, String kind, String configJson, String credentialRef, Instant at) {
    return new SourceInstance(
        id,
        kind,
        id.value(),
        new SourceConfig(1, configJson),
        credentialRef,
        true,
        SourceSchedule.DAEMON_DEFAULTS,
        at,
        at);
  }

  /** 꺼진 소스(같은 값, enabled false). */
  public static SourceInstance disabled(SourceInstance source) {
    return new SourceInstance(
        source.id(),
        source.kind(),
        source.displayName(),
        source.config(),
        source.credentialRef(),
        false,
        source.schedule(),
        source.createdAt(),
        source.updatedAt());
  }

  /** 소스별 주기를 가진 켜진 noop 소스. */
  public static SourceInstance scheduled(SourceInstanceId id, SourceSchedule schedule, Instant at) {
    return new SourceInstance(
        id,
        NOOP_KIND,
        id.value(),
        new SourceConfig(1, "{}"),
        "env:NOOP_TOKEN",
        true,
        schedule,
        at,
        at);
  }

  /** noop 종류의 증분 어댑터 — 수집 동작만 준다(페이지를 쓰지 않는 공급자 호출·대기 모사). */
  public static SourceAdapter adapter(Consumer<CollectionInput> collect) {
    return adapter(collect, sink -> {});
  }

  /** noop 종류의 증분 어댑터 — 입력을 받은 뒤 페이지를 쓴다. */
  public static SourceAdapter adapter(Consumer<CollectionInput> collect, Consumer<PageSink> pages) {
    return new SourceAdapter() {
      @Override
      public String kind() {
        return NOOP_KIND;
      }

      @Override
      public void validateConfig(SourceConfig config) {}

      @Override
      public CollectionCoverage coverage(
          SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
        return CollectionCoverage.INCREMENTAL;
      }

      @Override
      public void collect(CollectionInput input, PageSink sink) {
        collect.accept(input);
        pages.accept(sink);
      }
    };
  }

  /** noop 종류의 증분 어댑터 — 페이지를 쓰는 수집 동작을 준다. */
  public static SourceAdapter paging(Consumer<PageSink> collect) {
    return adapter(input -> {}, collect);
  }

  /** 저장·조회는 delegate에 맡기고 목록(listEnabled)만 바꾼 저장소 — 순서를 바꾸거나 목록 읽기 실패를 만든다. */
  public static SourceInstanceStore listing(
      SourceInstanceStore delegate, Supplier<List<SourceInstance>> listEnabled) {
    return new SourceInstanceStore() {
      @Override
      public void save(SourceInstance instance) {
        delegate.save(instance);
      }

      @Override
      public Optional<SourceInstance> get(SourceInstanceId id) {
        return delegate.get(id);
      }

      @Override
      public List<SourceInstance> listEnabled() {
        return listEnabled.get();
      }
    };
  }
}
