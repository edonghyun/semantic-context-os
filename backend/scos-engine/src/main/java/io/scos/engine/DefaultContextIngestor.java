package io.scos.engine;

import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.engine.support.projection.SyncReport;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.SpaceId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link DocumentIngestor}에 위임하고 결과를 공개 타입으로 바꾼다. */
final class DefaultContextIngestor implements ContextIngestor {
  private final DocumentIngestor delegate;

  DefaultContextIngestor(DocumentIngestor delegate) {
    this.delegate = delegate;
  }

  @Override
  public IngestReport ingest(
      SpaceId space, String sourceId, List<SourceDocument> documents, Set<String> liveDocumentIds) {
    SyncReport report =
        delegate.sync(space, sourceId, documents, liveDocumentIds, Map.of(), () -> {});
    List<IngestFailure> failures =
        report.errors().stream()
            .map(e -> new IngestFailure(e.uri(), e.stage(), e.message()))
            .toList();
    return new IngestReport(report.documents(), report.units(), report.deleted(), failures);
  }
}
