package io.scos.harness;

import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/**
 * 하네스 프로필(ADR-0017): Clock·Embedder **포트 구현**을 하네스 입력으로 바꾼다. 이 클래스는 제품 bootJar에 없고(src/harness →
 * scos-harness-adapters.jar), 하네스가 `-Dloader.path`로 얹을 때만 `io.scos` 스캔에 들어온다. 설정은 TS 하네스와 같은 환경변수
 * 이름(SCOS_HARNESS_NOW/CASSETTE/MISS_LOG, 완화 바인딩)이고 누락은 Bean Validation이 기동을 거부한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("harness")
@EnableConfigurationProperties(HarnessConfiguration.HarnessProperties.class)
public class HarnessConfiguration {
  @Validated
  @ConfigurationProperties("scos.harness")
  public record HarnessProperties(@NotBlank String now, @NotNull Path cassette, Path missLog) {}

  @Bean
  Clock fixedClock(HarnessProperties props) {
    return Clock.fixed(Instant.parse(props.now()), ZoneOffset.UTC);
  }

  @Bean
  Embedder cassetteEmbedder(HarnessProperties props, ObjectMapper json) {
    return new CassetteEmbedder(props.cassette(), props.missLog(), json);
  }

  /** 재정렬 생성기도 카세트 재생 — scos.rerank.provider=none이면 조립이 쓰지 않는다. */
  @Bean
  TextGenerator cassetteTextGenerator(HarnessProperties props, ObjectMapper json) {
    return new CassetteTextGenerator(props.cassette(), props.missLog(), json);
  }
}
