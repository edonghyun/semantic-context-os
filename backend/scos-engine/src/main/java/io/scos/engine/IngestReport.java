package io.scos.engine;

import java.util.List;

/** {@link ContextIngestor#ingest} 한 번의 결과. deleted = 지운 유닛 수(문서 수 아님). */
public record IngestReport(int documents, int units, int deleted, List<IngestFailure> failures) {
  public IngestReport {
    failures = List.copyOf(failures);
  }
}
