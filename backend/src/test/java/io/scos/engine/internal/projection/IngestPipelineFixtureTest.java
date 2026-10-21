package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * G3·G4 동등성: harness/fixtures/p4/expected-pipeline.json(TS createIngestPipeline이 표본 문서 4건에서 만든 유닛)과
 * Java 파이프라인의 결과가 id·파티션·제목·본문·해시·토큰·출처·중요도·링크·태그까지 전부 같다. 표본: frontmatter(flow·블록 태그, 잘못된 파티션)·중첩
 * 헤딩·펜스 안 `#`·800 토큰 초과 문단 분할·4096자 초과 문단 반분·중복 섹션·반복 헤딩 순번·링크 정규화(상대·위키·앵커·외부)·초소형
 * 섹션·CRLF·NBSP·주석.
 */
@DisplayName("수집 파이프라인은 표본 문서에서 TS와 같은 유닛을 만든다(G3·G4)")
class IngestPipelineFixtureTest {
  static final int SAMPLE_DOCUMENTS = 4;

  static JsonNode fixture() {
    Path file =
        Path.of(
            System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"),
            "expected-pipeline.json");
    try {
      return new ObjectMapper().readTree(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Stream<Named<JsonNode>> cases() {
    return StreamSupport.stream(fixture().get("cases").spliterator(), false)
        .map(c -> Named.of(c.get("document").get("documentId").asString(), c));
  }

  static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    if (arr != null) arr.forEach(n -> out.add(n.asString()));
    return out;
  }

  /** 유닛 하나의 비교용 값(TS 기대 JSON과 같은 필드). 별칭·임베딩은 파이프라인이 비워 둔다. */
  record UnitView(
      String id,
      String partition,
      String title,
      String content,
      String contentHash,
      int tokens,
      String tier,
      String provenanceSource,
      String provenanceDocument,
      String provenanceUri,
      String capturedAt,
      int importance,
      List<String> links,
      List<String> tags,
      boolean noAliases,
      boolean noEmbedding) {
    static UnitView of(ContextUnit a) {
      return new UnitView(
          a.id(),
          a.partition(),
          a.title(),
          a.content(),
          a.contentHash(),
          a.tokens(),
          a.tier().wire(),
          a.provenance().sourceId(),
          a.provenance().documentId(),
          a.provenance().uri(),
          a.provenance().capturedAt(),
          a.importance(),
          a.links(),
          a.tags(),
          a.aliases().isEmpty(),
          a.embedding().isEmpty());
    }

    static UnitView expected(JsonNode e) {
      JsonNode p = e.get("provenance");
      return new UnitView(
          e.get("id").asString(),
          e.get("partition").asString(),
          e.get("title").asString(),
          e.get("content").asString(),
          e.get("contentHash").asString(),
          e.get("tokens").asInt(),
          e.get("tier").asString(),
          p.get("sourceId").asString(),
          p.get("documentId").asString(),
          p.get("uri").asString(),
          p.get("capturedAt").asString(),
          e.get("importance").asInt(),
          strings(e.get("links")),
          strings(e.get("tags")),
          true,
          true);
    }
  }

  static SourceDocument document(JsonNode d) {
    return new SourceDocument(
        d.get("sourceId").asString(),
        d.get("documentId").asString(),
        d.get("uri").asString(),
        d.get("title").asString(),
        d.get("body").asString(),
        d.get("createdAt").asString(),
        d.get("updatedAt").asString());
  }

  @Test
  @DisplayName("표본 문서는 4건이다")
  void hasFourSamples() {
    // when·then
    assertThat(cases()).hasSize(SAMPLE_DOCUMENTS);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("표본 문서의 유닛이 TS와 순서·필드 단위로 같다")
  void unitsMatchTs(JsonNode c) {
    // given
    List<UnitView> expected =
        StreamSupport.stream(c.get("units").spliterator(), false).map(UnitView::expected).toList();

    // when
    List<ContextUnit> actual =
        new IngestPipeline(new JtokkitTokenizer()).apply(document(c.get("document")));

    // then
    assertThat(actual.stream().map(UnitView::of).toList()).containsExactlyElementsOf(expected);
  }
}
