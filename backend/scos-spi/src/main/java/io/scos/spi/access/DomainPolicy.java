package io.scos.spi.access;

import io.scos.spi.connector.SourceInstanceId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 조직 허용 목록과 원천 인스턴스 조직 도메인의 교집합. */
public record DomainPolicy(Set<String> orgDomains, Map<SourceInstanceId, String> instanceDomains) {
  public DomainPolicy {
    orgDomains =
        orgDomains.stream()
            .map(d -> d.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    instanceDomains =
        instanceDomains.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, e -> e.getValue().toLowerCase(Locale.ROOT)));
  }

  public boolean admits(String email, SourceInstanceId instance) {
    int separator = email.lastIndexOf('@');
    if (separator < 0) return false;
    String domain = email.substring(separator + 1).toLowerCase(Locale.ROOT);
    return orgDomains.contains(domain) && domain.equals(instanceDomains.get(instance));
  }
}
