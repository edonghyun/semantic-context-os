package io.scos.boardarchive.projection;

import io.scos.spi.projection.SourceDocument;
import java.util.*;

public record Projection(
    List<SourceDocument> documents, Set<String> liveDocumentIds, Map<String, String> hashes) {
  public Projection {
    documents = List.copyOf(documents);
    liveDocumentIds = Set.copyOf(liveDocumentIds);
    hashes = Map.copyOf(hashes);
  }
}
