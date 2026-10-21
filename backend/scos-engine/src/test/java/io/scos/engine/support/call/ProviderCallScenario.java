package io.scos.engine.support.call;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 공급자 호출 실행기 테스트의 연결을 소유한다: 가짜 시계, 실제로 기다리지 않는 대기(시계만 전진), 정책·지표 선택, 그리고 이름 있는 공급자 각본(Script). 본문은
 * "어떤 각본으로 부르면 몇 번 부르고 얼마나 기다려 무엇으로 끝나는가"만 쓴다.
 */
final class ProviderCallScenario {
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final String OPERATION = "slack.history";
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final MutableClock clock = new MutableClock(T0);
  private final List<Duration> waits = new ArrayList<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private ProviderCallPolicy policy = ProviderCallPolicy.defaults(() -> 0.0);
  private boolean waitsGetCancelled;
  private boolean metered;

  /** 재시도 예산(maxElapsed)만 바꾼 정책. 요청 timeout 30s·5회·지터 0. */
  ProviderCallScenario budget(Duration maxElapsed) {
    policy = new ProviderCallPolicy(REQUEST_TIMEOUT, 5, maxElapsed, () -> 0.0);
    return this;
  }

  /** 재시도 대기에 들어가면 그 사이 토큰이 취소된다. */
  ProviderCallScenario waitsGetCancelled() {
    waitsGetCancelled = true;
    return this;
  }

  /** 실행기가 이 시나리오의 지표 레지스트리에 기록한다. */
  ProviderCallScenario metered() {
    metered = true;
    return this;
  }

  void advanceClock(Duration d) {
    clock.advance(d);
  }

  Outcome call(Script script) {
    return call(script, CancellationToken.none());
  }

  /** 각본대로 행동하는 공급자를 실행기로 부른다. 공급자 실패는 결과에 담고, 분류기의 탈출 예외는 그대로 던진다. */
  Outcome call(Script script, CancellationToken cancel) {
    ProviderCallExecutor executor =
        new ProviderCallExecutor(
            policy,
            clock,
            this::waitFor,
            metered ? ProviderCallMetrics.of(registry) : TestMetrics.providerCalls());
    Outcome outcome = new Outcome();
    try {
      outcome.value =
          executor.execute(
              OPERATION,
              timeout -> script.attempt(outcome, timeout, clock),
              script::classify,
              cancel);
    } catch (CallFailureException e) {
      outcome.failure = e.failure();
    } finally {
      outcome.interruptedAfter = Thread.interrupted(); // 테스트 스레드 정리 + 관찰
    }
    return outcome;
  }

  /** 이미 인터럽트된 스레드에서 부른다. */
  Outcome callOnInterruptedThread(Script script) {
    Thread.currentThread().interrupt();
    return call(script);
  }

  /** 기본 실행기(defaults(clock))로 부른다. */
  Outcome callWithDefaults(Script script) {
    Outcome outcome = new Outcome();
    outcome.value =
        ProviderCallExecutor.defaults(clock, TestMetrics.providerCalls())
            .execute(
                OPERATION,
                timeout -> script.attempt(outcome, timeout, clock),
                script::classify,
                CancellationToken.none());
    return outcome;
  }

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  MeterRegistry metrics() {
    return registry;
  }

  private boolean waitFor(Duration d, CancellationToken cancel) {
    if (waitsGetCancelled) {
      cancel.cancel();
      return true;
    }
    waits.add(d);
    clock.advance(d);
    return cancel.isCancelled();
  }

  /** 한 번의 실행 결과: 돌려준 값 또는 실패, 공급자 호출 수, 시도별 timeout. */
  static final class Outcome {
    private final List<Duration> timeouts = new ArrayList<>();
    private int calls;
    private String value;
    private CallFailure failure;
    private boolean interruptedAfter;

    /** 실행이 끝난 시점에 호출 스레드의 인터럽트 표시가 남아 있었다. */
    boolean threadStillInterrupted() {
      return interruptedAfter;
    }

    int calls() {
      return calls;
    }

    List<Duration> timeouts() {
      return List.copyOf(timeouts);
    }

    Optional<String> value() {
      return Optional.ofNullable(value);
    }

    /** 실패했으면 그 종류. */
    Optional<Kind> failureKind() {
      return Optional.ofNullable(failure).map(CallFailure::kind);
    }
  }

  /** 공급자 각본: 처음 몇 번 어떻게 실패하고, 시도마다 시간이 얼마나 걸리는가. */
  static final class Script {
    static final String VALUE = "ok";

    private final int failures;
    private final Kind kind;
    private final Optional<Duration> retryAfter;
    private Duration firstAttemptTakes = Duration.ZERO;
    private Duration eachAttemptTakes = Duration.ZERO;
    private boolean interruptsThread;
    private boolean classifierEscapes;
    private boolean refusesHeader;

    private Script(int failures, Kind kind, Optional<Duration> retryAfter) {
      this.failures = failures;
      this.kind = kind;
      this.retryAfter = retryAfter;
    }

    static Script succeeds() {
      return new Script(0, Kind.DEPENDENCY_UNAVAILABLE, Optional.empty());
    }

    /** 재시도 가능한 실패(dependency_unavailable)를 n번 낸 뒤 성공한다. */
    static Script failsThenSucceeds(int n) {
      return new Script(n, Kind.DEPENDENCY_UNAVAILABLE, Optional.empty());
    }

    /** 재시도 가능한 실패를 계속 낸다. */
    static Script alwaysUnavailable() {
      return new Script(Integer.MAX_VALUE, Kind.DEPENDENCY_UNAVAILABLE, Optional.empty());
    }

    static Script alwaysFailsWith(Kind kind) {
      return new Script(Integer.MAX_VALUE, kind, Optional.empty());
    }

    /** 첫 호출이 rate_limited(Retry-After)이고 다음은 성공한다. */
    static Script rateLimitedOnce(Duration retryAfter) {
      return new Script(1, Kind.RATE_LIMITED, Optional.of(retryAfter));
    }

    /** 분류기가 탈출 예외를 던진다(래핑·재시도 대상이 아님). */
    static Script classifierEscapes() {
      Script s = alwaysUnavailable();
      s.classifierEscapes = true;
      return s;
    }

    /** 마지막 방어(ProviderHttp)가 보낼 수 없는 헤더 값을 거부한다. 분류기는 커넥터 분류기처럼 API 밖 예외를 재시도 가능한 전송 실패로 본다. */
    static Script refusesHeader() {
      Script s = alwaysUnavailable();
      s.refusesHeader = true;
      return s;
    }

    /** 호출이 스레드를 인터럽트한 채 실패한다(HTTP 클라이언트가 인터럽트를 IOException으로 바꾸는 경우). */
    static Script interruptsThread() {
      Script s = alwaysUnavailable();
      s.interruptsThread = true;
      return s;
    }

    Script firstAttemptTakes(Duration d) {
      firstAttemptTakes = d;
      return this;
    }

    Script eachAttemptTakes(Duration d) {
      eachAttemptTakes = d;
      return this;
    }

    private String attempt(Outcome outcome, Duration timeout, MutableClock clock)
        throws IOException {
      outcome.timeouts.add(timeout);
      int call = ++outcome.calls;
      clock.advance(call == 1 ? firstAttemptTakes.plus(eachAttemptTakes) : eachAttemptTakes);
      if (interruptsThread) Thread.currentThread().interrupt();
      if (refusesHeader) throw new UnsendableHeaderException("Authorization");
      if (call <= failures) throw new IOException("provider failure " + call);
      return VALUE;
    }

    private CallFailure classify(Exception e) {
      if (classifierEscapes) throw new UnsupportedOperationException("escape");
      if (kind == Kind.RATE_LIMITED) return CallFailure.rateLimited(e.getMessage(), retryAfter);
      return CallFailure.of(kind, e.getMessage());
    }
  }
}
