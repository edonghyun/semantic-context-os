package io.scos.boot;

import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.WorkClaimPort;
import io.scos.collection.internal.projection.ProjectionMetrics;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.collection.internal.projection.Projectors;
import io.scos.connector.slack.SlackProjector;
import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.projection.SourceSync;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.Tokenizer;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/** 투영 모듈 조립 — 적재·인제스트 유스케이스는 앱의 단일 TransactionManager(= 엔진 DataSource)를 쓴다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProjectionConfiguration.ProjectionProperties.class)
public class ProjectionConfiguration {
  /**
   * SCOS_SOURCE_SPACES(소스 id → 공간 id JSON 객체), 기본 공간은 SCOS_SPACE_ID(ADR-0009 환경변수 이름 유지). 기동 시 검증.
   */
  @Validated
  @ConfigurationProperties("scos.projection")
  public record ProjectionProperties(
      @DefaultValue("") String sourceSpaces,
      @DefaultValue("legacy") @NotBlank String defaultSpace) {}

  @Bean
  UnitImporter unitImporter(
      PlatformTransactionManager txm, UnitStore units, KeywordIndex keywords, VectorIndex vectors) {
    return new UnitImporter(new TransactionTemplate(txm), units, keywords, vectors);
  }

  @Bean
  Tokenizer tokenizer() {
    return new JtokkitTokenizer();
  }

  @Bean
  IngestPipeline ingestPipeline(Tokenizer tokenizer) {
    return new IngestPipeline(tokenizer);
  }

  @Bean
  SourceSync sourceSync(
      PlatformTransactionManager txm,
      UnitStore units,
      KeywordIndex keywords,
      VectorIndex vectors,
      SpaceMeta meta,
      Embedder embedder,
      EmbeddingCache cache,
      IngestPipeline pipeline,
      Clock clock) {
    return new DocumentIngestor(
        new TransactionTemplate(txm),
        units,
        keywords,
        vectors,
        meta,
        embedder,
        cache,
        pipeline,
        clock);
  }

  @Bean
  SlackProjector slackProjector(ObjectMapper json) {
    return new SlackProjector(json);
  }

  /** 등록된 kind별 투영기 전부(커넥터마다 Projector 빈 하나). */
  @Bean
  Projectors projectors(List<Projector> all) {
    return Projectors.of(all);
  }

  @Bean
  ProjectionSpaces projectionSpaces(ProjectionProperties props, ObjectMapper json) {
    return ProjectionSpaces.fromJson(props.sourceSpaces(), new SpaceId(props.defaultSpace()), json);
  }

  @Bean
  ProjectionRun projectionRun(
      OutboxPort outbox,
      SourceInstanceStore sources,
      RawItemStore rawItems,
      Projectors projectors,
      SourceSync ingestor,
      ProjectionSpaces spaces,
      WorkClaimPort claims,
      LeaseHeartbeats heartbeats,
      Clock clock,
      ProjectionMetrics metrics,
      Progress progress) {
    return new ProjectionRun(
        outbox,
        sources,
        rawItems,
        projectors,
        ingestor,
        spaces,
        ProjectionRetryPolicy.defaults(),
        claims,
        CollectionConfiguration.owner(),
        heartbeats,
        clock,
        metrics,
        progress);
  }
}
