package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector.ContentReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Notion 블록 렌더러(TS notion-block-renderer.ts, F6 T6.7): 표본마다 본문 줄과 제목이 TS와 바이트 단위로 같다. 골든은
 * harness/fixtures/notion/expected-renderer.json(TS NotionProjector로 뽑은 본문·제목)이다 — 제목 세
 * 단계·목록·to_do·code· rich_text 문단 대체·지원하지 않는 블록·중첩 들여쓰기·형제 생성시각 정렬·읽을 수 없는 본문·순환·제목 속성 순서.
 */
@DisplayName("Notion 블록 렌더러는 본문 줄·제목이 TS와 같다(F6 T6.7)")
class NotionBlockRendererTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR =
      Path.of(System.getProperty("scos.fixtures.notion.dir", "../harness/fixtures/notion"));

  /** 표본 페이지 id(TS 생성기와 같다). */
  static final String PAGE = "page-r";

  /** 제목이 비면 TS 투영기가 쓰는 자리표시(렌더러 titleOf는 ""를 낸다). */
  static final String NO_TITLE = "(제목 없음)";

  static final Instant OBSERVED = Instant.parse("2026-10-15T00:00:00Z");

  final NotionBlockRenderer renderer = new NotionBlockRenderer(JSON);

  static Stream<Arguments> cases() {
    JsonNode golden;
    try {
      golden = JSON.readTree(Files.readString(DIR.resolve("expected-renderer.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return StreamSupport.stream(golden.get("cases").spliterator(), false)
        .map(c -> Arguments.of(c.get("label").asString(), c));
  }

  /** 표본 블록 → 원본(metadata의 type은 표본 값 그대로 — 문자열이 아닐 수 있다). */
  static RawItem block(JsonNode b) {
    ObjectNode metadata = JSON.createObjectNode();
    metadata.set("type", b.get("type"));
    metadata.put("parentBlockId", b.get("parent").asString());
    return new RawItem(
        NotionBlockRenderer.BLOCK_PREFIX + b.get("id").asString(),
        "notion.block",
        "v",
        Optional.empty(),
        b.get("created").isNull() ? Optional.empty() : Optional.of(b.get("created").asString()),
        Optional.empty(),
        OBSERVED,
        metadata.toString(),
        b.get("content").isNull()
            ? Optional.empty()
            : Optional.of(new RawContent.Inline("application/json", b.get("content").toString())));
  }

  /** 표본의 블록 원본들. */
  static List<RawItem> blocks(JsonNode golden) {
    List<RawItem> out = new ArrayList<>();
    golden.get("blocks").forEach(b -> out.add(block(b)));
    return out;
  }

  /** TS 투영기 제목에서 렌더러 titleOf 값으로: 자리표시 "(제목 없음)"은 titleOf ""다. */
  static String expectedTitle(JsonNode golden) {
    String title = golden.get("title").asString();
    return title.equals(NO_TITLE) ? "" : title;
  }

  static final ContentReader INLINE =
      item -> item.content().map(c -> ((RawContent.Inline) c).valueJson());

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("본문은 TS 렌더러 줄을 빈 줄로 이은 것과 같다")
  void bodyMatchesTs(String label, JsonNode golden) {
    // when
    List<String> lines = renderer.render(PAGE, renderer.childrenByParent(blocks(golden)), INLINE);

    // then
    assertThat(String.join("\n\n", lines)).isEqualTo(golden.get("body").asString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("제목은 TS titleOf와 같다")
  void titleMatchesTs(String label, JsonNode golden) {
    // when
    String title = renderer.titleOf(Optional.of(golden.get("pageRaw").asString()));

    // then
    assertThat(title).isEqualTo(expectedTitle(golden));
  }
}
