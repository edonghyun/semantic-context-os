package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.connector.googledrive.internal.SheetRanges.SheetRange;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/** 시트 범위 나누기(TS sheet-ranges.spec.ts): 100×100 칸, 탭 이름 따옴표, GRID가 아닌 시트 제외, A1 열 이름. */
@DisplayName("시트 범위 나누기는 TS sheet-ranges와 같다(100×100 칸)")
class SheetRangesTest {
  static JsonNode workbook(String sheetsJson) {
    return GoogleDriveFixtures.JSON.readTree("{\"sheets\":" + sheetsJson + "}");
  }

  @Test
  @DisplayName("탭 이름을 작은따옴표로 감싸고 안의 따옴표는 겹쳐 쓴다")
  void escapesTitle() {
    // given
    JsonNode book =
        workbook(
            "[{\"properties\":{\"sheetId\":2,\"title\":\"O'Brien\","
                + "\"gridProperties\":{\"rowCount\":1,\"columnCount\":1}}}]");

    // when
    List<SheetRange> ranges = SheetRanges.of(book);

    // then
    assertThat(ranges).extracting(SheetRange::range).containsExactly("'O''Brien'!A1:A1");
  }

  @Test
  @DisplayName("행·열을 100칸씩 빠짐없이 나눈다")
  void coversGrid() {
    // given
    JsonNode book =
        workbook(
            "[{\"properties\":{\"sheetId\":2,\"title\":\"A\","
                + "\"gridProperties\":{\"rowCount\":101,\"columnCount\":101}}}]");

    // when
    List<SheetRange> ranges = SheetRanges.of(book);

    // then
    assertThat(ranges)
        .extracting(SheetRange::range)
        .containsExactly("'A'!A1:CV100", "'A'!CW1:CW100", "'A'!A101:CV101", "'A'!CW101:CW101");
  }

  @Test
  @DisplayName("범위 키는 시트 id와 0부터 센 시작·끝 행·열이다")
  void keysNameGridCells() {
    // given
    JsonNode book =
        workbook(
            "[{\"properties\":{\"sheetId\":2,\"title\":\"A\","
                + "\"gridProperties\":{\"rowCount\":101,\"columnCount\":1}}}]");

    // when
    List<SheetRange> ranges = SheetRanges.of(book);

    // then
    assertThat(ranges).extracting(SheetRange::key).containsExactly("2:0:0:100:1", "2:100:0:101:1");
  }

  @Test
  @DisplayName("격자가 없는 OBJECT 시트는 범위를 만들지 않는다")
  void skipsObjectSheets() {
    // given
    JsonNode book =
        workbook("[{\"properties\":{\"sheetId\":7,\"title\":\"Chart\",\"sheetType\":\"OBJECT\"}}]");

    // when
    List<SheetRange> ranges = SheetRanges.of(book);

    // then
    assertThat(ranges).isEmpty();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"0,A", "25,Z", "26,AA", "51,AZ", "52,BA", "701,ZZ", "702,AAA"})
  @DisplayName("열 번호를 A1 열 이름으로 쓴다")
  void namesColumns(long index, String name) {
    // when·then
    assertThat(SheetRanges.column(index)).isEqualTo(name);
  }

  @ParameterizedTest(name = "{0}행 × {1}열")
  @CsvSource({"0,5", "5,0"})
  @DisplayName("행이나 열이 0인 격자는 범위가 없다")
  void emptyGridHasNoRanges(int rows, int columns) {
    // given
    JsonNode book =
        workbook(
            "[{\"properties\":{\"sheetId\":1,\"title\":\"A\",\"gridProperties\":{\"rowCount\":"
                + rows
                + ",\"columnCount\":"
                + columns
                + "}}}]");

    // when
    List<SheetRange> ranges = SheetRanges.of(book);

    // then
    assertThat(ranges).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "시트 id가 소수|{\"sheetId\":1.5,\"title\":\"A\",\"gridProperties\":{\"rowCount\":1,\"columnCount\":1}}",
        "행이 음수|{\"sheetId\":1,\"title\":\"A\",\"gridProperties\":{\"rowCount\":-1,\"columnCount\":1}}",
        "열이 음수|{\"sheetId\":1,\"title\":\"A\",\"gridProperties\":{\"rowCount\":1,\"columnCount\":-1}}",
        "행이 문자열|{\"sheetId\":1,\"title\":\"A\",\"gridProperties\":{\"rowCount\":\"1\",\"columnCount\":1}}",
        "제목이 수|{\"sheetId\":1,\"title\":7,\"gridProperties\":{\"rowCount\":1,\"columnCount\":1}}"
      })
  @DisplayName("격자 속성이 정수·문자열 계약을 어기면 TS 메시지로 거부한다")
  void rejectsInvalidGrid(String label, String properties) {
    // given
    JsonNode book = workbook("[{\"properties\":" + properties + "}]");

    // when
    Throwable thrown = catchThrowable(() -> SheetRanges.of(book));

    // then
    assertThat(thrown).hasMessage("Invalid spreadsheet grid");
  }
}
