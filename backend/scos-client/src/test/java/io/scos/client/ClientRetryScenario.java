package io.scos.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 외부 열기 실패와 대기 중 취소를 결정론적으로 제어한다. */
final class ClientRetryScenario {
  private static final Duration RETRY = Duration.ofSeconds(1);
  private final List<Duration> waits = new ArrayList<>();
  private int failures;
  private boolean cancelWaiting;
  private int attempts;

  void givenOpenFailures(int failures) {
    this.failures = failures;
  }

  void cancelDuringRetryWait() {
    cancelWaiting = true;
  }

  Optional<String> open() {
    return ClientStartup.openWithRetry(this::attempt, 3, RETRY, this::waitForRetry);
  }

  private String attempt() {
    if (++attempts <= failures) throw new IllegalStateException("합성 색인 열기 실패");
    return "opened";
  }

  private boolean waitForRetry(Duration delay) {
    waits.add(delay);
    return cancelWaiting;
  }

  List<Duration> waits() {
    return List.copyOf(waits);
  }

  int attempts() {
    return attempts;
  }
}
