package io.scos.connector.googledrive.internal;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Sheets 원본의 평문(TS renderSpreadsheet): 시트마다 `## 시트 제목`, 그 아래 범위 데이터의 행마다 값이 있는 칸을 ` | `로 — 칸은 `<A1
 * 주소>: <표시 값>`에 수식·메모·링크를 덧붙인다(값·수식·메모·링크가 모두 비면 뺀다). 주소는 범위 시작 행·열(JS Number — 없으면 0) 더하기 위치다. D6:
 * 셀 펼침은 TS와 같게 둔다(유닛 정책 개선은 T7.11 제안).
 */
public final class SheetsRenderer {
  private static final long ALPHABET = 26;

  private SheetsRenderer() {}

  public static String spreadsheet(JsonNode workbook) {
    List<String> sheets = new ArrayList<>();
    for (JsonNode sheet : JsText.objects(workbook.get("sheets"))) sheets.add(sheet(sheet));
    return String.join("\n\n", sheets);
  }

  private static String sheet(JsonNode sheet) {
    List<String> lines = new ArrayList<>();
    lines.add("## " + JsText.text(JsText.object(sheet.get("properties")).get("title")));
    for (JsonNode grid : JsText.objects(sheet.get("data"))) {
      double startRow = start(grid.get("startRow"));
      double startColumn = start(grid.get("startColumn"));
      List<JsonNode> rows = JsText.objects(grid.get("rowData"));
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        List<String> cells = new ArrayList<>();
        List<JsonNode> values = JsText.objects(rows.get(rowIndex).get("values"));
        for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
          String cell =
              cell(values.get(columnIndex), startColumn + columnIndex, startRow + rowIndex + 1);
          if (!cell.isEmpty()) cells.add(cell);
        }
        if (!cells.isEmpty()) lines.add(String.join(" | ", cells));
      }
    }
    return String.join("\n", lines);
  }

  /** TS `Number(v ?? 0)`. */
  private static double start(JsonNode value) {
    return value == null || value.isNull() ? 0 : JsText.toNumber(value);
  }

  private static String cell(JsonNode cell, double column, double row) {
    String formula = JsText.text(JsText.object(cell.get("userEnteredValue")).get("formulaValue"));
    String value = JsText.text(cell.get("formattedValue"));
    String note = JsText.text(cell.get("note"));
    String link = JsText.text(cell.get("hyperlink"));
    if (value.isEmpty() && formula.isEmpty() && note.isEmpty() && link.isEmpty()) return "";
    return columnName(column)
        + JsText.number(row)
        + ": "
        + value
        + (formula.isEmpty() ? "" : " (수식: " + formula + ")")
        + (note.isEmpty() ? "" : " (메모: " + note + ")")
        + (link.isEmpty() ? "" : " (" + link + ")");
  }

  /** TS columnName: 0부터 센 열 번호의 A1 이름. 1 미만(NaN 포함)이면 빈 이름. */
  static String columnName(double index) {
    double number = index + 1;
    return number > 0 ? name(number) : "";
  }

  private static String name(double number) {
    char last = (char) ('A' + (long) ((number - 1) % ALPHABET));
    double rest = Math.floor((number - 1) / ALPHABET);
    return rest > 0 ? name(rest) + last : String.valueOf(last);
  }
}
