package io.scos.client.source.memory;

import io.scos.client.ClientJson;
import io.scos.client.DocumentMetadata;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 자동 메모리 파일(`claude.memory.file`) → 문서 하나. 문서 id는 `memory/<dir>/<name>.md`. 제목은 frontmatter
 * `name`(없으면 파일 이름). 시각은 파일 수정 시각(없으면 관측 시각)이다. 본문은 파일 그대로 넘긴다: frontmatter는 엔진이 수집 때 떼므로 여기서 지우지
 * 않는다.
 */
public final class MemoryProjector implements Projector {
  public static final String KIND = MemoryAdapter.KIND;
  private static final String DOCUMENT_PREFIX = "memory/";
  private static final Pattern FRONTMATTER =
      Pattern.compile("\\A\\uFEFF?---\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);
  private static final Pattern NAME =
      Pattern.compile("^name:[ \\t]*(.*?)[ \\t]*$", Pattern.MULTILINE);

  private final ObjectMapper json = ClientJson.MAPPER;

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new TreeMap<>();
    for (RawItem item : items) {
      if (!item.resourceType().equals(MemoryAdapter.RESOURCE_TYPE)) continue;
      String documentId =
          DocumentMetadata.documentIdOf(item).map(DocumentMetadata::documentId).orElse("");
      if (documentId.isEmpty()) continue;
      groups.computeIfAbsent(documentId, k -> new ArrayList<>()).add(item);
    }
    return groups;
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    if (items.isEmpty()) return Optional.empty();
    RawItem item = items.getFirst();
    Optional<String> text = textOf(reader.apply(item));
    if (text.isEmpty()) return Optional.empty();
    String path =
        documentId.startsWith(DOCUMENT_PREFIX)
            ? documentId.substring(DOCUMENT_PREFIX.length())
            : documentId;
    String modified = item.sourceUpdatedAt().orElseGet(() -> item.observedAt().toString());
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            "claude-memory://" + path,
            frontmatterName(text.get()).orElse(path.substring(path.lastIndexOf('/') + 1)),
            text.get(),
            // 생성 시각: 믿을 만한 birth time이 없어 수정 시각을 쓴다
            modified,
            modified));
  }

  /** 선두 frontmatter 블록의 `name:` 값(따옴표 제거). 없거나 비었으면 empty. */
  private static Optional<String> frontmatterName(String text) {
    Matcher block = FRONTMATTER.matcher(text);
    if (!block.find()) return Optional.empty();
    Matcher name = NAME.matcher(block.group(1));
    if (!name.find()) return Optional.empty();
    String value = name.group(1);
    if (value.length() >= 2
        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
        && value.charAt(value.length() - 1) == value.charAt(0)) {
      value = value.substring(1, value.length() - 1);
    }
    return value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private Optional<String> textOf(Optional<String> content) {
    if (content.isEmpty()) return Optional.empty();
    try {
      JsonNode text = json.readTree(content.get()).path("text");
      return text.isString() ? Optional.of(text.asString()) : Optional.empty();
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }
}
