package io.scos.boot;

import io.scos.connector.googledrive.GoogleDriveProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Drive 투영 조립(F7): 투영기 빈 하나를 등록하면 kind 레지스트리 `Projectors`가 google-drive 소스를 이 투영기로 투영한다
 * (ADR-0022).
 */
@Configuration(proxyBeanMethods = false)
public class GoogleDriveProjectionConfiguration {
  @Bean
  GoogleDriveProjector googleDriveProjector(ObjectMapper json) {
    return new GoogleDriveProjector(json);
  }
}
