package io.scos.boot;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceInstanceId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 접근 모드·수집 여부·조직 도메인 오버레이. */
@ConfigurationProperties("scos.access")
public record AccessProperties(
    Mode mode,
    @Nullable Boolean collect,
    List<String> orgDomains,
    Map<String, String> instanceDomains) {
  public AccessProperties(
      @Nullable Mode mode,
      @Nullable Boolean collect,
      @Nullable List<String> orgDomains,
      @Nullable Map<String, String> instanceDomains) {
    this.mode = mode == null ? Mode.SINGLE_USER : mode;
    if (this.mode == Mode.MULTI_USER && Boolean.FALSE.equals(collect))
      throw new IllegalStateException(
          "scos.access.collect=false is not allowed in multi-user mode");
    this.collect = collect;
    this.orgDomains = orgDomains == null ? List.of() : List.copyOf(orgDomains);
    this.instanceDomains = instanceDomains == null ? Map.of() : Map.copyOf(instanceDomains);
    for (String instance : this.instanceDomains.keySet()) {
      if (instance.isBlank()
          || instance.contains(".")
          || instance.contains(":")
          || instance.contains("%"))
        throw new IllegalStateException("invalid scos.access.instance-domains instance id");
    }
  }

  /** 검증된 소스 계정 도메인을 기본값으로 쓰며 명시 오버레이가 우선한다. */
  public DomainPolicy domainPolicy(Map<SourceInstanceId, String> accountEmails) {
    var domains = new HashMap<SourceInstanceId, String>();
    accountEmails.forEach(
        (instance, email) -> domains.put(instance, email.substring(email.lastIndexOf('@') + 1)));
    instanceDomains.forEach(
        (instance, domain) -> domains.put(new SourceInstanceId(instance), domain));
    return new DomainPolicy(Set.copyOf(orgDomains), domains);
  }

  public boolean collectAccess() {
    return mode == Mode.MULTI_USER || Boolean.TRUE.equals(collect);
  }

  public enum Mode {
    SINGLE_USER,
    MULTI_USER
  }
}
