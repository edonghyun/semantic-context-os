package io.scos.engine.internal.search;

import io.scos.engine.DocumentNotFoundException;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import java.util.List;
import java.util.Optional;

/**
 * read_document(CONTRACTS D). 페이지는 적재 순서, limit+1 읽어 nextOffset을 정한다. 제목·uri는 **문서**(첫 유닛) 기준 — TS가
 * 페이지 첫 유닛 제목을 내는 것은 결함 D1(사용자 결정 2026-10-13). 문서 끝을 넘는 offset은 **빈 페이지 + 더 없음**(사용자 결정 2026-10-13;
 * TS는 "문서를 찾을 수 없음"을 내는 결함). 문서가 공간에 없으면 예외.
 */
public final class DocumentReader {
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public record UnitView(String unitId, String title, String content) {}

  /** nextOffset이 비어 있으면 마지막 페이지. */
  public record Page(
      String title, String uri, List<UnitView> units, Optional<Integer> nextOffset) {}

  private final UnitStore units;

  public DocumentReader(UnitStore units) {
    this.units = units;
  }

  public Page read(SpaceId space, String documentId, int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > MAX_LIMIT)
      throw new InvalidArgument(
          "offset must be non-negative and limit must be between 1 and " + MAX_LIMIT);
    UnitStore.DocumentInfo info =
        units.getDocuments(space, List.of(documentId)).stream()
            .findFirst()
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
    List<ContextUnit> page =
        units.listByDocument(space, documentId, new UnitStore.Range(offset, limit + 1));
    List<UnitView> views =
        page.stream().limit(limit).map(u -> new UnitView(u.id(), u.title(), u.content())).toList();
    return new Page(
        info.title(),
        info.uri(),
        views,
        page.size() > limit ? Optional.of(offset + limit) : Optional.empty());
  }
}
