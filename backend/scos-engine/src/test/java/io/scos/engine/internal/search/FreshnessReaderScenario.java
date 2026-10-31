package io.scos.engine.internal.search;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.engine.Freshness;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 제한 범위 최신성에 필요한 수집 기록·공간·시계 조립. */
final class FreshnessReaderScenario {
  static final SpaceId SPACE = new SpaceId("legacy");
  static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
  static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");
  static final Instant OLDEST = Instant.parse("2026-10-13T00:00:00Z");
  static final Instant RECENT = Instant.parse("2026-10-13T11:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final Set<AccessKey> BOTH_SOURCE_KEYS =
      Set.of(
          AccessKey.parse("user:slack-a:U1"),
          AccessKey.parse("group:slack-a:C1"),
          AccessKey.parse("domain:drive-a:example.com"));

  private final RecordedSourceFreshness sources = new RecordedSourceFreshness();
  private final RecordingUnitMaintenance units =
      new RecordingUnitMaintenance(new MemoryUnitStore(), new MemoryVectorIndex());

  void recordSuccess(SourceInstanceId instance, Instant at) {
    sources.recordSuccess(instance, at);
  }

  Freshness read(Set<AccessKey> keys) {
    var reader = new FreshnessReader(Optional.of(units), Optional.of(sources), CLOCK);
    return reader.read(new AccessScope.Restricted(new Principal("member"), keys), SPACE);
  }

  void recordHiddenCapturedAt(Instant capturedAt) {
    units.upsert(
        SPACE,
        List.of(
            unit("hidden")
                .inDocument("hidden-document")
                .capturedAt(capturedAt.toString())
                .build()));
  }

  Freshness readWithoutSources(Set<AccessKey> keys) {
    var reader = new FreshnessReader(units, CLOCK);
    return reader.read(new AccessScope.Restricted(new Principal("member"), keys), SPACE);
  }

  Freshness readUnrestrictedWithoutMaintenance() {
    var reader = new FreshnessReader(Optional.empty(), Optional.of(sources), CLOCK);
    return reader.read(AccessScope.singleUser(), SPACE);
  }

  Freshness readWithoutMaintenance(Set<AccessKey> keys) {
    var reader = new FreshnessReader(Optional.empty(), Optional.of(sources), CLOCK);
    return reader.read(new AccessScope.Restricted(new Principal("member"), keys), SPACE);
  }

  int maintenanceReads() {
    return units.latestReads();
  }
}
