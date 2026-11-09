package io.scos.spi.connector;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.ProviderCallFakes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 접근 SPI 계약에 필요한 소스와 협력자만 연결한다. 공급자는 호출하지 않는다. */
final class AccessInputScenario {
  private final SourceDescriptor source =
      new SourceDescriptor(
          new SourceInstanceId("slack-a"),
          "slack",
          "팀 대화",
          new SourceConfig(1, "{\"conversationTypes\":[\"public_channel\"]}"),
          "env:SLACK_SYNTHETIC_TOKEN");

  private final SourceAdapter adapter =
      new SourceAdapter() {
        @Override
        public String kind() {
          return "slack";
        }

        @Override
        public void validateConfig(SourceConfig config) {}

        @Override
        public CollectionCoverage coverage(
            SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
          return CollectionCoverage.INCREMENTAL;
        }

        @Override
        public void collect(CollectionInput input, PageSink sink) {}
      };

  AccessInput input(List<String> externalIds) {
    return new AccessInput(
        source,
        externalIds,
        ProviderCallFakes.executor(),
        CancellationToken.none(),
        new DomainPolicy(Set.of(), Map.of()));
  }

  void collectWithDefault() {
    adapter.accessPolicy().collect(input(List.of()), new RecordedAclSink());
  }

  AccessModel defaultAccessModel() {
    return adapter.accessPolicy().model();
  }

  Set<String> defaultCarrierKinds() {
    return adapter.accessPolicy().carrierKinds();
  }

  Optional<RawAcl> defaultAcl(Instant observedAt) {
    return adapter.accessPolicy().localAcl(source, "C1:1700000000.000001", observedAt);
  }
}
