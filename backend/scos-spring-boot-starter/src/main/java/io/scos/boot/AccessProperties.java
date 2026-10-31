package io.scos.boot;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.SourceInstanceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

  public DomainPolicy domainPolicy() {
    return new DomainPolicy(
        Set.copyOf(orgDomains),
        instanceDomains.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    e -> new SourceInstanceId(e.getKey()), Map.Entry::getValue)));
  }

  public boolean collectAccess() {
    return mode == Mode.MULTI_USER || Boolean.TRUE.equals(collect);
  }

  public enum Mode {
    SINGLE_USER,
    MULTI_USER
  }
}
