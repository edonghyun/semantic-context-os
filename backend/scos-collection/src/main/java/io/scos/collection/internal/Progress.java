package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 작업 진행 신호(ADR-0020 §3 정체 판정): 페이지 커밋과 투영 문서 쓰기가 남긴다. 데몬 health는 사이클 끝뿐 아니라 이 신호로도 "진행 중"을 본다 — 큰
 * 백필·대량 초기 임베딩처럼 정상적으로 긴 사이클을 정체로 오판하지 않는다. 점유 갱신(heartbeat)은 진행이 아니다 — 점유를 쥔 채 멈춘 worker도 갱신은
 * 계속된다. 페이지 커밋은 소스별로도 남긴다 — 사이클이 장벽이 아니라 다른 소스의 진행이 멈춘 수집을 가리지 않게, 데몬은 수집 중인 시도마다 그 소스의 진행을 본다.
 */
public final class Progress {
  private final Clock clock;
  private final AtomicReference<Instant> last = new AtomicReference<>();
  private final Map<SourceInstanceId, Instant> bySource = new ConcurrentHashMap<>();

  public Progress(Clock clock) {
    this.clock = clock;
  }

  public void mark() {
    last.set(clock.instant());
  }

  /** 그 소스의 진행(페이지 커밋). 전체 진행에도 들어간다. */
  public void mark(SourceInstanceId source) {
    Instant now = clock.instant();
    last.set(now);
    bySource.put(source, now);
  }

  public Optional<Instant> lastAt() {
    return Optional.ofNullable(last.get());
  }

  public Optional<Instant> lastAt(SourceInstanceId source) {
    return Optional.ofNullable(bySource.get(source));
  }
}
