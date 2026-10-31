package io.scos.client.source.transcript;

import io.scos.client.ClientJson;
import io.scos.client.DocumentMetadata;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 트랜스크립트 줄(`claude.transcript.turn`) → 세션 문서 하나. 문서 id는 `transcript/<dir>/<session>`, 줄은 번호 순. 본문은
 * 턴 묶음({@link TurnBundles}), 제목은 `<프로젝트 폴더 이름> · <마지막 ai-title 또는 첫 발화 앞 60자>`.
 */
public final class TranscriptProjector implements Projector {
  public static final String KIND = TranscriptAdapter.KIND;
  private static final String DOCUMENT_PREFIX = "transcript/";
  private static final int TITLE_FALLBACK_CHARS = 60;

  private final ObjectMapper json = ClientJson.MAPPER;

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new TreeMap<>();
    for (RawItem item : items) {
      if (!item.resourceType().equals(TranscriptAdapter.TURN)) continue;
      String documentId =
          DocumentMetadata.documentIdOf(item).map(DocumentMetadata::documentId).orElse("");
      if (documentId.isEmpty()) continue;
      groups.computeIfAbsent(documentId, k -> new ArrayList<>()).add(item);
    }
    groups.values().forEach(list -> list.sort(Comparator.comparingLong(this::lineNumber)));
    return groups;
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    List<RawItem> sorted = new ArrayList<>(items);
    sorted.sort(Comparator.comparingLong(this::lineNumber));
    TurnBundles bundles = new TurnBundles();
    String cwd = "";
    String aiTitle = "";
    for (RawItem item : sorted) {
      Optional<JsonNode> parsed = parse(reader.apply(item));
      if (parsed.isEmpty()) continue;
      JsonNode line = parsed.get();
      if (cwd.isEmpty()) cwd = line.path("cwd").asString("");
      if (line.path("type").asString("").equals("ai-title")) {
        String candidate = line.path("aiTitle").asString("");
        if (!candidate.isEmpty()) aiTitle = candidate;
      }
      bundles.accept(line);
    }
    List<String> sections = bundles.finish();
    if (sections.isEmpty()) return Optional.empty();
    String subject =
        aiTitle.isEmpty()
            ? ToolSummary.clip(
                ToolSummary.firstLine(bundles.firstUtterance()), TITLE_FALLBACK_CHARS)
            : aiTitle;
    String project = projectName(cwd);
    String title = project.isEmpty() ? subject : project + " · " + subject;
    String path =
        documentId.startsWith(DOCUMENT_PREFIX)
            ? documentId.substring(DOCUMENT_PREFIX.length())
            : documentId;
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            "claude-transcript://" + path,
            title,
            String.join("\n\n", sections),
            nearestTimestamp(sorted),
            nearestTimestamp(sorted.reversed())));
  }

  private Optional<JsonNode> parse(Optional<String> content) {
    if (content.isEmpty()) return Optional.empty();
    try {
      JsonNode node = json.readTree(content.get());
      return node.isObject() ? Optional.of(node) : Optional.empty();
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  /** externalId `<dir>/<session>#<줄 번호>`의 숫자(문자열 순서가 아니라 숫자 순서로 정렬하려고). */
  private long lineNumber(RawItem item) {
    String id = item.externalId();
    try {
      return Long.parseLong(id.substring(id.lastIndexOf('#') + 1));
    } catch (NumberFormatException e) {
      return Long.MAX_VALUE;
    }
  }

  /** 순서대로 처음 만나는 시각. 시각이 없는 줄(ai-title 등)은 건너뛰어 가장 가까운 줄의 것을 쓴다. */
  private static String nearestTimestamp(List<RawItem> ordered) {
    return ordered.stream()
        .flatMap(item -> item.sourceUpdatedAt().stream())
        .filter(t -> !t.isEmpty())
        .findFirst()
        .orElse("");
  }

  private static String projectName(String cwd) {
    String trimmed = cwd.replaceAll("[/\\\\]+$", "");
    int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
    return trimmed.substring(cut + 1);
  }
}
