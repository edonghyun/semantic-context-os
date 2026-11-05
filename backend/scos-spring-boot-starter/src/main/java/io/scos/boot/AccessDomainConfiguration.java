package io.scos.boot;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.connector.googledrive.internal.GoogleDriveConfiguration;
import io.scos.engine.EventLog;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.source.SourceInstanceId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 읽기와 수집이 공유하는 기동 시점 도메인 정책. 소스·조직 설정 변경은 재기동 후 반영한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessProperties.class)
public class AccessDomainConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(AccessDomainConfiguration.class);

  @Bean
  DomainPolicy domainPolicy(AccessProperties properties, SourceQueries queries, ObjectMapper json) {
    var drives =
        queries.listSources().stream()
            .filter(s -> s.kind().equals(GoogleDriveAdapter.KIND))
            .toList();
    Map<SourceInstanceId, String> accounts = new HashMap<>();
    for (SourceInstance drive : drives)
      accountEmail(drive, json).ifPresent(email -> accounts.put(drive.id(), email));
    DomainPolicy policy = properties.domainPolicy(accounts);
    if (properties.collectAccess())
      for (SourceInstance drive : drives) warnIfUnavailable(drive.id(), policy);
    return policy;
  }

  private Optional<String> accountEmail(SourceInstance source, ObjectMapper json) {
    try {
      return Optional.of(GoogleDriveConfiguration.read(json, source.config()).accountEmail());
    } catch (JacksonException | CallFailureException invalid) {
      return Optional.empty();
    }
  }

  private void warnIfUnavailable(SourceInstanceId source, DomainPolicy policy) {
    String domain = policy.instanceDomains().get(source);
    if (domain != null && policy.orgDomains().contains(domain)) return;
    EventLog.warn(LOG, "access.domain.unavailable")
        .source(source)
        .detail("reason", domain == null ? "missing_instance_domain" : "outside_org_domains")
        .log();
  }
}
