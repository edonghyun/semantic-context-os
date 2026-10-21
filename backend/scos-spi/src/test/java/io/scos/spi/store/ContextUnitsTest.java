package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 테스트 데이터 빌더의 사본 계약 — 사본이 필드를 조용히 잃으면 수명주기 테스트가 다른 유닛을 본다. */
@DisplayName("유닛 빌더의 사본은 필드를 잃지 않는다(TESTING §4.2 C7)")
class ContextUnitsTest {
  /** 빌더 기본값이 아닌 값을 모든 필드에 가진 유닛. */
  static final ContextUnit EVERY_FIELD_SET =
      ContextUnits.unit("u-1")
          .partition("/rules")
          .titled("제목")
          .content("본문")
          .contentHash("hash-1")
          .tokens(42)
          .compacted("u-0")
          .source("slack")
          .inDocument("doc-9")
          .uri("slack://C1/p1")
          .capturedAt("2026-10-02T00:00:00.000Z")
          .importance(9)
          .validFrom("2026-09-01T00:00:00.000Z")
          .invalidatedAt("2026-10-03T00:00:00.000Z")
          .linksTo("doc-2")
          .tagged("tag-a")
          .aliased("별칭")
          .embedding(1, 0)
          .build();

  @Test
  @DisplayName("from(유닛)의 사본은 원본과 모든 필드가 같다")
  void fromCopiesEveryField() {
    // when
    ContextUnit copy = ContextUnits.from(EVERY_FIELD_SET).build();

    // then
    assertThat(copy).usingRecursiveComparison().isEqualTo(EVERY_FIELD_SET);
  }
}
