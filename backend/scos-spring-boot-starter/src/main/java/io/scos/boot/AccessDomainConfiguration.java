package io.scos.boot;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.connector.googledrive.internal.GoogleDriveConfiguration;
import io.scos.connector.tiro.TiroAdapter;
import io.scos.engine.EventLog;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.source.SourceInstanceId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 읽기와 수집이 공유하는 기동 시점 도메인 정책. 소스·조직 설정 변경은 재기동 후 반영한다. 도메인 키를 내는 원천(Drive·Tiro)은 접근 수집이 켜져 있을 때 인스턴스
 * 도메인이 없거나 조직 밖이면 경고한다. 계정 이메일로 기본값을 채우는 것은 Drive뿐이다(Tiro는 명시 설정만).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessProperties.class)
public class AccessDomainConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(AccessDomainConfiguration.class);
  private static final Set<String> DOMAIN_KEY_KINDS =
      Set.of(GoogleDriveAdapter.KIND, TiroAdapter.KIND);

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
      for (SourceInstance source : queries.listSources())
        if (DOMAIN_KEY_KINDS.contains(source.kind())) warnIfUnavailable(source.id(), policy);
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
