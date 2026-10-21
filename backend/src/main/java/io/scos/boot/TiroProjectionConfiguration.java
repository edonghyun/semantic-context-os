package io.scos.boot;

import io.scos.connector.tiro.TiroProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Tiro 투영 조립(F8): 투영기 빈 하나를 등록하면 kind 레지스트리 `Projectors`가 tiro 소스를 이 투영기로 투영한다(ADR-0022). */
@Configuration(proxyBeanMethods = false)
public class TiroProjectionConfiguration {
  @Bean
  TiroProjector tiroProjector(ObjectMapper json) {
    return new TiroProjector(json);
  }
}
