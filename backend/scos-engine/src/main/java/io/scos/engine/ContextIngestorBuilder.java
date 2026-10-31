package io.scos.engine;

import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.engine.internal.projection.EmbedderUnitEmbedding;
import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.engine.internal.projection.KeywordOnlyUnitEmbedding;
import io.scos.engine.internal.projection.UnitEmbedding;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.VectorIndex;
import java.time.Clock;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link ContextIngestor} 조립. 필수는 maintenance·keywords·vectors·meta와 embedder 또는 keywordOnly이고
 * build는 저장소에 접근하지 않는다.
 */
public final class ContextIngestorBuilder {
  private sealed interface UnitEmbeddingChoice {
    record WithEmbedder(Embedder embedder) implements UnitEmbeddingChoice {}

    record KeywordOnly() implements UnitEmbeddingChoice {}
  }

  private @Nullable UnitMaintenance maintenance;
  private @Nullable KeywordIndex keywords;
  private @Nullable VectorIndex vectors;
  private @Nullable SpaceMeta meta;
  private @Nullable UnitEmbeddingChoice embeddingChoice;
  private EmbeddingCache cache = EmbeddingCache.NONE;
  private TransactionOperations transactions = TransactionOperations.withoutTransaction();
  private Clock clock = Clock.systemUTC();

  public ContextIngestorBuilder() {}

  public ContextIngestorBuilder maintenance(UnitMaintenance maintenance) {
    this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    return this;
  }

  public ContextIngestorBuilder keywords(KeywordIndex keywords) {
    this.keywords = Objects.requireNonNull(keywords, "keywords");
    return this;
  }

  public ContextIngestorBuilder vectors(VectorIndex vectors) {
    this.vectors = Objects.requireNonNull(vectors, "vectors");
    return this;
  }

  public ContextIngestorBuilder meta(SpaceMeta meta) {
    this.meta = Objects.requireNonNull(meta, "meta");
    return this;
  }

  /** 유닛마다 이 임베더로 벡터를 만든다. 마지막으로 부른 {@link #keywordOnly()} 또는 이 설정을 따른다. */
  public ContextIngestorBuilder embedder(Embedder embedder) {
    this.embeddingChoice =
        new UnitEmbeddingChoice.WithEmbedder(Objects.requireNonNull(embedder, "embedder"));
    return this;
  }

  /** 벡터를 만들지 않고 키워드 색인만 채운다. 마지막으로 부른 {@link #embedder} 또는 이 설정을 따른다. */
  public ContextIngestorBuilder keywordOnly() {
    this.embeddingChoice = new UnitEmbeddingChoice.KeywordOnly();
    return this;
  }

  /** 지정하지 않으면 {@link EmbeddingCache#NONE}. */
  public ContextIngestorBuilder cache(EmbeddingCache cache) {
    this.cache = Objects.requireNonNull(cache, "cache");
    return this;
  }

  /** 지정하지 않으면 트랜잭션 없이 실행한다. */
  public ContextIngestorBuilder transactions(TransactionOperations transactions) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    return this;
  }

  /** 지정하지 않으면 {@link Clock#systemUTC()}. */
  public ContextIngestorBuilder clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    return this;
  }

  /** 필수 포트가 없으면 {@code IllegalStateException("<name> is required")}. */
  public ContextIngestor build() {
    return new DefaultContextIngestor(buildDocumentIngestor());
  }

  private DocumentIngestor buildDocumentIngestor() {
    return new DocumentIngestor(
        transactions,
        require(maintenance, "maintenance"),
        require(keywords, "keywords"),
        require(vectors, "vectors"),
        require(meta, "meta"),
        embedding(require(embeddingChoice, "embedder")),
        new IngestPipeline(new JtokkitTokenizer()),
        clock);
  }

  private UnitEmbedding embedding(UnitEmbeddingChoice choice) {
    return switch (choice) {
      case UnitEmbeddingChoice.WithEmbedder with ->
          new EmbedderUnitEmbedding(with.embedder(), cache);
      case UnitEmbeddingChoice.KeywordOnly ignored -> new KeywordOnlyUnitEmbedding();
    };
  }

  private static <T> T require(@Nullable T value, String name) {
    if (value == null) throw new IllegalStateException(name + " is required");
    return value;
  }
}
