package io.scos.connector.notion.internal;

import java.util.Locale;
import java.util.Optional;

/** Notion 검색의 휴지통 단계(TS `phase: "active" | "trash"`): 살아 있는 페이지를 다 본 뒤 휴지통 페이지를 본다. */
enum SearchPhase {
  ACTIVE,
  TRASH;

  String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** 체크포인트 값("active"·"trash")을 읽는다. 다른 값이면 비어 있다. */
  static Optional<SearchPhase> fromWire(String value) {
    for (SearchPhase phase : values()) if (phase.wire().equals(value)) return Optional.of(phase);
    return Optional.empty();
  }
}
