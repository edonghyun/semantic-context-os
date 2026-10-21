package io.scos.store.postgres;

import io.scos.connector.googledrive.GoogleDriveProjector;
import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.store.postgres.internal.Bigrams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * F7 T7.11 측정 프로브(게이트 아님, perfTest): Drive 시트의 유닛 정책 후보가 유닛 수·토큰·키워드 bigram 행을 얼마나 바꾸는지 잰다. 제품 정책은
 * 바꾸지 않는다(D6 — 지금은 TS와 동등). 현재 정책은 제품 투영기(GoogleDriveProjector)를 그대로 쓰고, 후보는 같은 셀 데이터로 이 프로브가 본문을
 * 만든다. 셀은 합성(실데이터 없음)이고 모양 셋은 운영에서 흔한 원장·메모·희소 대형 시트를 흉내 낸다. 결과는 로그의 `RESULT |` 표 줄이다.
 */
@Tag("perf")
@DisplayName("시트 유닛 정책 프로브는 후보별 유닛·토큰·bigram 행을 기록한다(F7 T7.11)")
class SheetUnitPolicyProbeTest {
  static final Logger log = LoggerFactory.getLogger(SheetUnitPolicyProbeTest.class);
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant AT = Instant.parse("2026-10-16T00:00:00Z");
  static final int CHUNK = 100;
  static final int ROW_CAP = 200;

  /** 시트 모양: 행·열 수, 채움 비율, 셀 값 만들기. 첫 행은 머리글. */
  record Shape(String name, int rows, int columns, double fill, Function<Random, String> value) {}

  static final List<Shape> SHAPES =
      List.of(
          new Shape(
              "원장(숫자 1000×12)",
              1000,
              12,
              1.0,
              r -> String.format(Locale.ROOT, "%,d", r.nextInt(1_000_000))),
          new Shape("메모(한글 300×6)", 300, 6, 0.9, SheetUnitPolicyProbeTest::memo),
          new Shape("희소 대형(2000×30, 3%)", 2000, 30, 0.03, r -> "항목 " + r.nextInt(500)));

  static final String[] WORDS = {
    "결제", "정산", "배포", "장애", "회의", "고객", "요청", "검토", "완료", "지연", "원인", "조치", "일정", "담당", "확인"
  };

  static String memo(Random r) {
    StringBuilder b = new StringBuilder();
    int n = 6 + r.nextInt(10);
    for (int i = 0; i < n; i++) b.append(WORDS[r.nextInt(WORDS.length)]).append(' ');
    return b.toString().trim();
  }

  /** 셀 격자(null은 빈 칸). */
  static String[][] grid(Shape s) {
    Random r = new Random(7);
    String[][] g = new String[s.rows()][s.columns()];
    for (int c = 0; c < s.columns(); c++) g[0][c] = "열" + (c + 1);
    for (int row = 1; row < s.rows(); row++)
      for (int c = 0; c < s.columns(); c++)
        if (r.nextDouble() < s.fill()) g[row][c] = s.value().apply(r);
    return g;
  }

  record Measure(int units, long tokens, long bigramRows) {}

  static Measure measure(SourceDocument doc) {
    List<ContextUnit> units = new IngestPipeline(new JtokkitTokenizer()).apply(doc);
    long tokens = units.stream().mapToLong(ContextUnit::tokens).sum();
    long bigrams =
        units.stream()
            .mapToLong(
                u ->
                    Bigrams.bigramCounts((u.title() + "\n" + u.content()).toLowerCase(Locale.ROOT))
                        .size())
            .sum();
    return new Measure(units.size(), tokens, bigrams);
  }

  // ---- 현재 정책(제품 투영기) ----

  static SourceDocument current(Shape s, String[][] g) {
    String fileId = "sheet";
    ObjectNode file =
        JSON.createObjectNode().put("id", fileId).put("name", s.name()).put("version", "1");
    ObjectNode envelope = JSON.createObjectNode();
    envelope.set("file", file);
    envelope.put("extraction", "google_sheets");
    envelope.putArray("limitations");
    ObjectNode workspace = envelope.putObject("workspace");
    workspace.putArray("sheets").addObject().putObject("properties").put("title", "시트1");
    ArrayNode keys = envelope.putArray("sheetRangeKeys");
    List<RawItem> items = new ArrayList<>();
    for (int row = 0; row < s.rows(); row += CHUNK)
      for (int col = 0; col < s.columns(); col += CHUNK) {
        String key = "0:" + row + ":" + col;
        keys.add("sheet:" + fileId + ":" + key);
        ObjectNode range = JSON.createObjectNode().put("version", "1").put("fileId", fileId);
        ObjectNode sheet = range.putObject("workspace").putArray("sheets").addObject();
        sheet.putObject("properties").put("title", "시트1");
        ObjectNode data =
            sheet.putArray("data").addObject().put("startRow", row).put("startColumn", col);
        ArrayNode rowData = data.putArray("rowData");
        for (int r = row; r < Math.min(row + CHUNK, s.rows()); r++) {
          ArrayNode values = rowData.addObject().putArray("values");
          for (int c = col; c < Math.min(col + CHUNK, s.columns()); c++) {
            ObjectNode cell = values.addObject();
            if (g[r][c] != null) cell.put("formattedValue", g[r][c]);
          }
        }
        items.add(
            item(
                "sheet:" + fileId + ":" + key,
                "gdrive.sheet",
                range,
                "{\"fileId\":\"" + fileId + "\"}"));
      }
    // 범위 키를 다 채운 뒤에 파일 원본을 만든다(원본 JSON은 만든 순간의 값)
    items.addFirst(item("file:" + fileId, "gdrive.file", envelope, "{}"));
    GoogleDriveProjector projector = new GoogleDriveProjector(JSON);
    Map<String, List<RawItem>> groups = projector.groupIntoDocuments(items);
    return projector
        .project(
            "probe",
            "file:" + fileId,
            groups.get("file:" + fileId),
            i -> i.content().map(c -> ((RawContent.Inline) c).valueJson()))
        .orElseThrow();
  }

  static RawItem item(String id, String type, ObjectNode value, String metadata) {
    return new RawItem(
        id,
        type,
        "v",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        AT,
        metadata,
        Optional.of(new RawContent.Inline("application/json", value.toString())));
  }

  // ---- 후보(같은 셀 데이터, 본문만 다르게) ----

  /** A: 행마다 머리글=값(주소 없음), 시트 헤딩 한 번. */
  static SourceDocument headerRows(Shape s, String[][] g) {
    StringBuilder b = new StringBuilder("# " + s.name() + "\n\n## 시트1");
    for (int r = 1; r < s.rows(); r++) {
      List<String> cells = new ArrayList<>();
      for (int c = 0; c < s.columns(); c++) if (g[r][c] != null) cells.add(g[0][c] + "=" + g[r][c]);
      if (!cells.isEmpty())
        b.append('\n').append(r + 1).append("행: ").append(String.join("; ", cells));
    }
    return doc(s, b.toString());
  }

  /** B: 주소 없는 표(값을 " | "로), 시트 헤딩 한 번, 빈 칸 자리는 비운다. */
  static SourceDocument plainTable(Shape s, String[][] g) {
    StringBuilder b = new StringBuilder("# " + s.name() + "\n\n## 시트1");
    for (int r = 0; r < s.rows(); r++) {
      boolean any = false;
      List<String> cells = new ArrayList<>();
      for (int c = 0; c < s.columns(); c++) {
        cells.add(g[r][c] == null ? "" : g[r][c]);
        any |= g[r][c] != null;
      }
      if (any) b.append('\n').append(String.join(" | ", cells));
    }
    return doc(s, b.toString());
  }

  /** C: 현재 렌더링(A1 주소)이되 시트마다 앞 200행만 색인하고 나머지는 생략 줄. */
  static SourceDocument rowCap(Shape s, String[][] g) {
    StringBuilder b = new StringBuilder("# " + s.name() + "\n\n## 시트1");
    for (int r = 0; r < Math.min(ROW_CAP, s.rows()); r++) {
      List<String> cells = new ArrayList<>();
      for (int c = 0; c < s.columns(); c++)
        if (g[r][c] != null) cells.add(column(c) + (r + 1) + ": " + g[r][c]);
      if (!cells.isEmpty()) b.append('\n').append(String.join(" | ", cells));
    }
    if (s.rows() > ROW_CAP) b.append("\n\n(이하 ").append(s.rows() - ROW_CAP).append("행 색인 생략)");
    return doc(s, b.toString());
  }

  /** E: 현재 렌더링(A1 주소, 행마다 " | ")이되 범위마다의 시트 헤딩을 빼고 시트 헤딩을 한 번만 둔다. */
  static SourceDocument oneHeading(Shape s, String[][] g) {
    StringBuilder b = new StringBuilder("# " + s.name() + "\n\n## 시트1");
    for (int r = 0; r < s.rows(); r++) {
      List<String> cells = new ArrayList<>();
      for (int c = 0; c < s.columns(); c++)
        if (g[r][c] != null) cells.add(column(c) + (r + 1) + ": " + g[r][c]);
      if (!cells.isEmpty()) b.append('\n').append(String.join(" | ", cells));
    }
    return doc(s, b.toString());
  }

  /** D: 현재 렌더링(A1 주소)이되 숫자·기호만 있는 셀(글자가 없는 셀)은 색인하지 않는다. */
  static SourceDocument textCellsOnly(Shape s, String[][] g) {
    StringBuilder b = new StringBuilder("# " + s.name() + "\n\n## 시트1");
    for (int r = 0; r < s.rows(); r++) {
      List<String> cells = new ArrayList<>();
      for (int c = 0; c < s.columns(); c++)
        if (g[r][c] != null && g[r][c].codePoints().anyMatch(Character::isLetter))
          cells.add(column(c) + (r + 1) + ": " + g[r][c]);
      if (!cells.isEmpty()) b.append('\n').append(String.join(" | ", cells));
    }
    return doc(s, b.toString());
  }

  static String column(int index) {
    StringBuilder n = new StringBuilder();
    for (int v = index + 1; v > 0; v = (v - 1) / 26) n.insert(0, (char) ('A' + (v - 1) % 26));
    return n.toString();
  }

  static SourceDocument doc(Shape s, String body) {
    return new SourceDocument(
        "probe",
        "file:sheet",
        "https://example.test",
        s.name(),
        body,
        "2026-10-16T00:00:00.000Z",
        "2026-10-16T00:00:00.000Z");
  }

  @Test
  @DisplayName("시트 유닛 정책 후보의 유닛·토큰·bigram 행을 잰다")
  void measuresPolicies() {
    // when·then: 측정만 한다 — 결과는 로그의 표 줄이다
    log.info("RESULT | 시트 | 정책 | 유닛 | 토큰 | bigram 행 | 유닛 비 |");
    for (Shape s : SHAPES) {
      String[][] g = grid(s);
      Measure base = measure(current(s, g));
      if (base.units() <= 0)
        throw new IllegalStateException("given 준비 실패: 현재 정책의 유닛이 있어야 비율을 낸다 — " + s.name());
      print(s, "현재(TS 동등)", base, base);
      print(s, "E 범위 헤딩 생략", measure(oneHeading(s, g)), base);
      print(s, "A 행마다 머리글=값", measure(headerRows(s, g)), base);
      print(s, "B 주소 없는 표", measure(plainTable(s, g)), base);
      print(s, "C 앞 200행만", measure(rowCap(s, g)), base);
      print(s, "D 글자 없는 셀 제외", measure(textCellsOnly(s, g)), base);
    }
  }

  static void print(Shape s, String policy, Measure m, Measure base) {
    log.info(
        String.format(
            Locale.ROOT,
            "RESULT | %s | %s | %d | %d | %d | %.2f |",
            s.name(),
            policy,
            m.units(),
            m.tokens(),
            m.bigramRows(),
            (double) m.units() / base.units()));
  }
}
