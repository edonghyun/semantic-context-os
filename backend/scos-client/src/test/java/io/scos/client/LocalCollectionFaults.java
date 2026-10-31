package io.scos.client;

import io.scos.client.collect.CollectionTask;
import io.scos.client.collect.IncrementalCollector.Source;
import io.scos.engine.ContextIngestor;
import io.scos.engine.IngestFailure;
import io.scos.engine.IngestReport;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 대체 구현이 아니라 운영 협력자를 실행한 뒤 실패·취소를 주입한다. */
final class LocalCollectionFaults {
  private boolean projectionFails;
  private boolean transcriptsIsolated;
  private boolean transcriptFails;
  private boolean cancels;
  private boolean creationFails;

  void failProjection() {
    projectionFails = true;
  }

  void isolateTranscripts() {
    transcriptsIsolated = true;
  }

  void failAfterTranscript() {
    transcriptFails = true;
  }

  void cancelAfterDocument() {
    cancels = true;
  }

  void failCreation() {
    creationFails = true;
  }

  void reset() {
    projectionFails = false;
    transcriptsIsolated = false;
    transcriptFails = false;
    cancels = false;
  }

  CollectionTask create(CollectionTask delegate) {
    if (creationFails) {
      creationFails = false;
      throw new IllegalArgumentException("합성 수집기 생성 실패");
    }
    return delegate;
  }

  ContextIngestor ingestor(ContextIngestor real) {
    if (!projectionFails && !transcriptsIsolated) return real;
    return (space, sourceId, documents, live) -> {
      if (projectionFails) throw new IllegalStateException("합성 투영 실패");
      if (!transcriptsIsolated || !sourceId.equals("claude-transcript"))
        return real.ingest(space, sourceId, documents, live);
      IngestReport report = real.ingest(space, sourceId, List.of(), live);
      List<IngestFailure> failures =
          documents.stream()
              .map(document -> new IngestFailure(document.uri(), "pipeline", "합성 실패"))
              .toList();
      return new IngestReport(report.documents(), report.units(), report.deleted(), failures);
    };
  }

  List<Source> sources(List<Source> production, Runnable cancel) {
    return production.stream()
        .map(
            source -> {
              if ((!transcriptFails && !cancels)
                  || !source.adapter().kind().equals("claude-transcript")) return source;
              return new Source(
                  transcriptFails ? failingAfter(source.adapter()) : source.adapter(),
                  cancels ? cancelling(source.projector(), cancel) : source.projector(),
                  source.projectedTypes());
            })
        .toList();
  }

  private SourceAdapter failingAfter(SourceAdapter real) {
    return new SourceAdapter() {
      @Override
      public String kind() {
        return real.kind();
      }

      @Override
      public void validateConfig(SourceConfig config) {
        real.validateConfig(config);
      }

      @Override
      public CollectionCoverage coverage(
          SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
        return real.coverage(config, mode, checkpoint);
      }

      @Override
      public void collect(CollectionInput input, PageSink sink) {
        real.collect(input, sink);
        throw new IllegalArgumentException("합성 어댑터 실패");
      }
    };
  }

  private Projector cancelling(Projector real, Runnable cancel) {
    return new Projector() {
      @Override
      public String kind() {
        return real.kind();
      }

      @Override
      public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
        return real.groupIntoDocuments(items);
      }

      @Override
      public Optional<SourceDocument> project(
          String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
        Optional<SourceDocument> document = real.project(sourceId, documentId, items, reader);
        cancel.run();
        return document;
      }
    };
  }
}
