package io.scos.engine.internal.projection;

import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Slack 밖 kind의 투영기 대역: "notes" 원본 전부를 문서 하나로 묶고 원본 본문을 줄마다 잇는다. */
public final class NotesProjector implements Projector {
  public static final String KIND = "notes";
  public static final String DOCUMENT = "notes/all";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new TreeMap<>();
    if (!items.isEmpty()) groups.put(DOCUMENT, List.copyOf(items));
    return groups;
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    String body = String.join("\n", items.stream().map(i -> reader.apply(i).orElse("")).toList());
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            "notes://" + sourceId + "/" + documentId,
            "노트 모음",
            body,
            "2026-10-14T00:00:00Z",
            "2026-10-14T00:00:00Z"));
  }
}
