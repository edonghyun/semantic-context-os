package io.scos.server;

import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.Maintenance;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceRegistrar;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.spi.store.UnitStore;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsConfiguration.DatabaseProperties.class)
public class OpsConfiguration {
  /** 기동 시 DB 버전 검사의 기대값(ADR-0027). 빈 값은 바인딩에서 거부한다. */
  @Validated
  @ConfigurationProperties("scos.database")
  public record DatabaseProperties(
      @DefaultValue("17.11") @NotBlank String expectedServerVersion,
      @DefaultValue("0.8.7") @NotBlank String expectedVectorVersion) {}

  /** 모든 명령(mcp·daemon 포함)이 Flyway 뒤, 명령 실행 전에 DB 버전을 확인한다 — 다르면 기동 거부(종료 코드 1). */
  @Bean
  SmartInitializingSingleton databaseVersionCheck(DatabaseProperties props, DataSource dataSource) {
    return () ->
        new DatabaseVersionCheck(
                new DatabaseVersionCheck.Expected(
                    props.expectedServerVersion(), props.expectedVectorVersion()),
                () -> DatabaseVersionCheck.observe(dataSource))
            .verify();
  }

  @Bean
  UnitJsonImport unitJsonImport(UnitImporter importer, ObjectMapper mapper) {
    return new UnitJsonImport(importer, mapper);
  }

  @Bean
  Commands commands(
      UnitJsonImport importer,
      SourceRegistrar registrar,
      CollectionRuntime collection,
      SourceInstanceStore sources,
      ProjectionRun projection,
      Maintenance maintenance,
      ObjectMapper json,
      QueryCommands queries,
      SnapshotShrinkGuard shrinkGuard,
      SourceAdapters adapters,
      Clock clock) {
    return new Commands(
        importer,
        registrar,
        collection,
        sources,
        projection,
        maintenance,
        json,
        queries,
        shrinkGuard,
        adapters,
        clock);
  }

  /** 운영 조회 명령: 결과는 stdout(CommandOutput), 최신성은 투영 공간마다. */
  @Bean
  QueryCommands queryCommands(
      SourceQueries queries,
      SourceAdapters adapters,
      UnitStore units,
      ProjectionSpaces spaces,
      Clock clock,
      ObjectMapper json) {
    return new QueryCommands(
        queries,
        adapters,
        new FreshnessReader(units, clock),
        spaces.spaces(),
        clock,
        new SourceQueryJson(json),
        CommandOutput.stdout());
  }
}
