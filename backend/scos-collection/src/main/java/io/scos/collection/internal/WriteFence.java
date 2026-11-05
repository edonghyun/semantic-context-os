package io.scos.collection.internal;

import io.scos.spi.connector.SourceDescriptor;
import java.time.Instant;
import java.util.Optional;

/** 같은 쓰기 트랜잭션에서 점유·run·출처·캡처한 설정을 이 순서로 잠그고 검증한다. */
public final class WriteFence {
  private final CollectionStores stores;

  public WriteFence(CollectionStores stores) {
    this.stores = stores;
  }

  public void requireWritable(
      WorkClaim claim, RunId run, Optional<SourceDescriptor> expectedSource, Instant at) {
    stores.claims().requireActive(claim, at);
    stores.runs().requireRunning(run, claim.source());
    expectedSource.ifPresent(
        expected -> {
          if (!claim.source().equals(expected.id()))
            throw new SourceConfigurationChangedException();
          stores
              .sources()
              .orElseThrow(
                  () -> new IllegalStateException("Source configuration store is not configured"))
              .requireCollectionConfiguration(expected);
        });
  }
}
