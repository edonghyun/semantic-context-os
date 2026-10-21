package io.scos.connector.googledrive.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import tools.jackson.databind.JsonNode;

/**
 * 시트 범위 나누기(TS sheetRanges): GRID 시트마다 100행 × 100열 칸으로 자르고, 탭 이름은 작은따옴표로 감싼다(공백·따옴표가 그대로 남게 — `'`는
 * `''`). 키는 `<sheetId>:<시작 행>:<시작 열>:<끝 행>:<끝 열>`(0부터, 끝은 제외), 범위는 A1 표기다. GRID가 아닌 시트는 건너뛴다.
 */
final class SheetRanges {
  static final int CELLS_PER_SIDE = 100;
  private static final int ALPHABET = 26;

  /** 범위 하나: 원본 id에 쓰는 키와 Sheets API의 ranges 값. */
  record SheetRange(String key, String range) {}

  private SheetRanges() {}

  static List<SheetRange> of(JsonNode workspace) {
    JsonNode sheets = workspace.get("sheets");
    if (sheets == null || !sheets.isArray())
      throw new GoogleProtocolException("Missing spreadsheet sheets");
    List<SheetRange> ranges = new ArrayList<>();
    for (JsonNode entry : sheets) {
      JsonNode properties = JsValues.object(JsValues.object(entry).get("properties"));
      JsonNode type = properties.get("sheetType");
      if (type != null && !JsValues.is(type, "GRID")) continue;
      JsonNode grid = JsValues.object(properties.get("gridProperties"));
      JsonNode title = properties.get("title");
      JsonNode rows = grid.get("rowCount");
      JsonNode columns = grid.get("columnCount");
      if (title == null
          || !title.isString()
          || !JsValues.isInteger(properties.get("sheetId"))
          || !JsValues.isInteger(rows)
          || !JsValues.isInteger(columns)
          || rows.asDouble() < 0
          || columns.asDouble() < 0) throw new GoogleProtocolException("Invalid spreadsheet grid");
      String sheetId = JsValues.string(properties.get("sheetId"));
      String quoted = "'" + title.asString().replace("'", "''") + "'!";
      long rowCount = rows.asLong();
      long columnCount = columns.asLong();
      // 칸 번호로 돈다 — 시작 행·열은 칸 번호 × 100(TS의 row += 100 루프와 같은 순서)
      LongStream.range(0, Math.ceilDiv(rowCount, CELLS_PER_SIDE))
          .map(r -> r * CELLS_PER_SIDE)
          .forEach(
              row ->
                  LongStream.range(0, Math.ceilDiv(columnCount, CELLS_PER_SIDE))
                      .map(c -> c * CELLS_PER_SIDE)
                      .forEach(
                          col -> {
                            long endRow = Math.min(row + CELLS_PER_SIDE, rowCount);
                            long endCol = Math.min(col + CELLS_PER_SIDE, columnCount);
                            ranges.add(
                                new SheetRange(
                                    sheetId + ":" + row + ":" + col + ":" + endRow + ":" + endCol,
                                    quoted
                                        + column(col)
                                        + (row + 1)
                                        + ":"
                                        + column(endCol - 1)
                                        + endRow));
                          }));
    }
    return ranges;
  }

  /** 0부터 센 열 번호 → A1 열 이름(A … Z, AA …). */
  static String column(long index) {
    return name(index + 1);
  }

  /** 1부터 센 열 번호의 이름: 26 이하면 글자 하나, 아니면 앞자리 이름 + 끝 글자. */
  private static String name(long number) {
    char last = (char) ('A' + (number - 1) % ALPHABET);
    return number <= ALPHABET ? String.valueOf(last) : name((number - 1) / ALPHABET) + last;
  }
}
