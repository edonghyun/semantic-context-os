package io.scos.engine;

import java.util.List;
import java.util.OptionalInt;

/** 문서 한 페이지. 제목·uri는 문서(첫 유닛) 기준이다. nextOffset이 비면 마지막 페이지다. */
public record DocumentPage(
    String documentId, String title, String uri, List<UnitView> units, OptionalInt nextOffset) {
  public DocumentPage {
    units = List.copyOf(units);
  }
}
