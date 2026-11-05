package io.scos.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.FixedSourceQueries;
import io.scos.collection.internal.SourceQueries;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** 접속 정보가 없는 PostgreSQL 포트로 조립만 검증한다. DB를 읽으면 준비 실패다. */
final class McpConfigurationScenario {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

  ApplicationContextRunner contextWithUnrestrictedAccess() {
    return context()
        .withBean(
            AccessParts.class,
            () ->
                new AccessParts.Unrestricted(
                    new PostgresIndexConfiguration()
                        .newUnitMaintenance(new DriverManagerDataSource())));
  }

  ApplicationContextRunner contextWithRestrictedAccess() {
    return context()
        .withBean(
            AccessParts.class,
            () ->
                new McpConfiguration()
                    .accessParts(
                        new AccessProperties(AccessProperties.Mode.MULTI_USER, null, null, null),
                        new DriverManagerDataSource(),
                        new DomainPolicy(Set.of(), Map.of())));
  }

  ApplicationContextRunner context() {
    return context(CLOCK, new FixedSourceQueries(List.of()));
  }

  ApplicationContextRunner context(Clock clock, SourceQueries queries) {
    DataSource dataSource = new DriverManagerDataSource();
    return new ApplicationContextRunner()
        .withUserConfiguration(McpConfiguration.class)
        .withPropertyValues("scos.mcp.space-id=legacy")
        .withBean(DataSource.class, () -> dataSource)
        .withBean(UnitStore.class, () -> PostgresIndexConfiguration.newUnitStore(dataSource))
        .withBean(
            UnitMaintenance.class,
            () -> new PostgresIndexConfiguration().newUnitMaintenance(dataSource))
        .withBean(KeywordIndex.class, () -> PostgresIndexConfiguration.newKeywordIndex(dataSource))
        .withBean(VectorIndex.class, () -> PostgresIndexConfiguration.newVectorIndex(dataSource))
        .withBean(Embedder.class, () -> FixedEmbedder.zero(2))
        .withBean(Clock.class, () -> clock)
        .withBean(SourceQueries.class, () -> queries)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            RerankProperties.class, () -> new RerankProperties("none", null, null, null, null));
  }
}
