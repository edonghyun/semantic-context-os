package io.scos.boot;

import io.scos.connector.notion.NotionProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Notion 투영기 조립(F6 T6.9): 빈 하나가 `Projectors`에 notion을 더한다(ADR-0022 §1). */
@Configuration(proxyBeanMethods = false)
public class NotionProjectionConfiguration {
  @Bean
  NotionProjector notionProjector(ObjectMapper json) {
    return new NotionProjector(json);
  }
}
