package io.scos.server.fault;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.spi.connector.SourceInstanceId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 프로세스 테스트용 장애 주입(DaemonProcessTest): 실제 bootJar에 `-Dloader.path`로만 얹는다. 소스 저장소가 치명 오류(Error)를 던져
 * 감독 루프를 죽인다. 첫 목록 읽기(데몬 기동 검증, ADR-0022 §5)는 빈 목록으로 통과시킨다 — 장애는 기동이 아니라 루프에서 나야 한다. 프로퍼티
 * `scos.test.daemon-loop-fault=error`일 때만 켜진다(다른 조립 테스트의 클래스패스 스캔에 걸려도 꺼져 있다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scos.test.daemon-loop-fault", havingValue = "error")
public class DaemonLoopFault {
  public static final String MESSAGE = "injected fatal error";

  @Bean
  @Primary
  SourceInstanceStore fatallyFailingSources() {
    AtomicInteger listings = new AtomicInteger();
    return new SourceInstanceStore() {
      @Override
      public void save(SourceInstance instance) {
        throw new LinkageError(MESSAGE);
      }

      @Override
      public Optional<SourceInstance> get(SourceInstanceId id) {
        throw new LinkageError(MESSAGE);
      }

      @Override
      public List<SourceInstance> listEnabled() {
        if (listings.getAndIncrement() == 0) return List.of();
        throw new LinkageError(MESSAGE);
      }
    };
  }
}
