package io.scos.support;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 실시간 heartbeat 스케줄러(자식 JVM 프로브·kill 테스트용). 결정적 테스트는 {@link ManualTaskScheduler}를 쓴다. */
public final class RealTimeScheduler {
  private RealTimeScheduler() {}

  public static ThreadPoolTaskScheduler create() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("test-heartbeat-");
    scheduler.setDaemon(true);
    scheduler.initialize();
    return scheduler;
  }
}
