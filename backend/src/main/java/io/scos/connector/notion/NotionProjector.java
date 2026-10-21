package io.scos.connector.notion;

import io.scos.connector.notion.internal.NotionBlockRenderer;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 페이지·블록 원본 → 페이지 단위 Markdown 문서(TS NotionProjector, notion-projector.ts). 페이지 원본 하나가 문서
 * 하나이고, 블록은 parentBlockId 사슬을 따라 올라가 만나는 첫 페이지의 문서에 들어간다(같은 id의 페이지와 child_page 블록이 있으면 페이지가 먼저).
 * 부모가 없거나 database·data source 소속이거나 순환이면 어느 문서에도 넣지 않는다. database·data source 원본은 문서가 아니다.
 *
 * <p>문서: 제목은 페이지 원본의 title 속성(없으면 "(제목 없음)"), 본문은 {@link NotionBlockRenderer} 줄을 빈 줄로 이은 것. 제목과 본문이
 * 모두 비면 건너뛴다. uri는 metadata url → publicUrl → `notion://<id>`, 생성시각은 원본 생성시각(없으면 관찰 시각), 수정시각은 페이지와
 * 블록 수정시각 가운데 (문자열) 최댓값(없으면 생성시각).
 */
public final class NotionProjector implements Projector {
  public static final String KIND = "notion";
  static final String PAGE_TYPE = "notion.page";
  static final String BLOCK_TYPE = "notion.block";
  static final String NO_TITLE = "(제목 없음)";
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;
  private final NotionBlockRenderer renderer;

  public NotionProjector(ObjectMapper json) {
    this.json = json;
    this.renderer = new NotionBlockRenderer(json);
  }

  @Override
  public String kind() {
    return KIND;
  }

  /** 문서 id 순(TreeMap — Projector 계약). TS는 페이지 삽입 순서지만 투영 실행은 묶음 순서에 기대지 않는다. */
  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, RawItem> index = new HashMap<>();
    for (RawItem item : items) index.put(item.externalId(), item);
    Map<String, List<RawItem>> groups = new TreeMap<>();
    for (RawItem item : items)
      if (item.resourceType().equals(PAGE_TYPE))
        groups.computeIfAbsent(item.externalId(), id -> new ArrayList<>()).add(item);
    for (RawItem item : items) {
      if (!item.resourceType().equals(BLOCK_TYPE)) continue;
      ancestorPage(item, index)
          .filter(groups::containsKey)
          .ifPresent(page -> groups.get(page).add(item));
    }
    return groups;
  }

  /** TS resolveAncestorPageId: 부모 사슬을 페이지까지 걷는다. 고아·container 소속·순환은 없음. */
  private Optional<String> ancestorPage(RawItem block, Map<String, RawItem> index) {
    Set<String> visited = new HashSet<>(Set.of(block.externalId()));
    RawItem current = block;
    while (true) {
      Optional<String> parentId = renderer.parentBlockIdOf(current);
      if (parentId.isEmpty()) return Optional.empty();
      RawItem direct = index.get(parentId.get());
      if (direct != null && direct.resourceType().equals(PAGE_TYPE))
        return Optional.of(direct.externalId());
      RawItem parent = index.get(NotionBlockRenderer.blockItemId(parentId.get()));
      if (parent == null) parent = direct;
      if (parent == null) return Optional.empty();
      if (parent.resourceType().equals(PAGE_TYPE)) return Optional.of(parent.externalId());
      // TS 방어(container 소속 제외): database·data source 원본은 `database:`·`data-source:` 접두라 맨 id 조회로 닿지
      // 않고,
      // 닿아도 parentBlockId가 없다 — 이 데이터 모양에서는 순환 가드만 실제로 걸린다(F6 중간 리뷰 m8)
      if (!parent.resourceType().equals(BLOCK_TYPE) || !visited.add(parent.externalId()))
        return Optional.empty();
      current = parent;
    }
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    Optional<RawItem> found =
        items.stream()
            .filter(i -> i.resourceType().equals(PAGE_TYPE) && i.externalId().equals(documentId))
            .findFirst();
    if (found.isEmpty()) return Optional.empty();
    RawItem page = found.get();
    List<RawItem> blocks = items.stream().filter(i -> i.resourceType().equals(BLOCK_TYPE)).toList();
    String title = renderer.titleOf(reader.apply(page));
    String body =
        String.join(
            "\n\n", renderer.render(page.externalId(), renderer.childrenByParent(blocks), reader));
    if (title.isEmpty() && body.isEmpty()) return Optional.empty();
    String createdAt = page.sourceCreatedAt().orElse(ISO_MILLIS.format(page.observedAt()));
    // TS maxIso: 문자열(코드 유닛) 비교로 가장 늦은 수정시각
    Optional<String> updatedAt =
        Stream.concat(Stream.of(page), blocks.stream())
            .flatMap(item -> item.sourceUpdatedAt().stream())
            .max(String::compareTo);
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            uri(page),
            title.isEmpty() ? NO_TITLE : title,
            body,
            createdAt,
            updatedAt.orElse(createdAt)));
  }

  private String uri(RawItem page) {
    JsonNode metadata = json.readTree(page.metadataJson());
    for (String key : List.of("url", "publicUrl")) {
      JsonNode value = metadata.isObject() ? metadata.get(key) : null;
      if (value != null && value.isString()) return value.asString();
    }
    return "notion://" + page.externalId();
  }
}
