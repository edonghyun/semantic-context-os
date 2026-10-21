package io.scos.boot;

import io.scos.connector.jira.JiraProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Jira 투영 조립(F5): 투영기 빈 하나를 등록하면 kind 레지스트리 `Projectors`가 jira 소스를 이 투영기로 투영한다(ADR-0022). */
@Configuration(proxyBeanMethods = false)
public class JiraProjectionConfiguration {
  @Bean
  JiraProjector jiraProjector(ObjectMapper json) {
    return new JiraProjector(json);
  }
}
