package io.scos.client.source.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientJson;
import io.scos.client.DocumentMetadata;
import io.scos.client.collect.LocalContentCapture;
import io.scos.client.source.CollectedPages;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.SourceDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("자동 메모리 투영기는 파일 본문과 제목을 보존한다(CG-1.4)")
class MemoryProjectorTest {
  private final MemoryProjector projector = new MemoryProjector();

  private static RawItem file(String externalId, String type, String textJson) {
    return file(externalId, type, textJson, Optional.empty());
  }

  private static RawItem file(
      String externalId, String type, String textJson, Optional<String> sourceUpdatedAt) {
    return new RawItem(
        externalId,
        type,
        "v1",
        Optional.empty(),
        Optional.empty(),
        sourceUpdatedAt,
        Instant.parse("2026-10-28T00:00:00Z"),
        DocumentMetadata.of("memory/" + externalId).toJson(),
        Optional.of(new LocalContentCapture().json("application/json", textJson)));
  }

  private Optional<SourceDocument> project(RawItem item) {
    var groups = projector.groupIntoDocuments(List.of(item));
    String documentId = "memory/" + item.externalId();
    return projector.project("src", documentId, groups.get(documentId), CollectedPages::read);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"이름 안의 공백\"", "'이름 안의 공백'"})
  @DisplayName("따옴표로 감싼 name은 바깥 따옴표를 벗겨 제목으로 쓴다")
  void unquotesName(String name) {
    // given
    String text = "---\nname: " + name + "\ntype: feedback\n---\n본문\n";
    RawItem item =
        file(
            "d/a.md",
            "claude.memory.file",
            ClientJson.MAPPER.createObjectNode().put("text", text).toString());
    // when
    SourceDocument doc = project(item).orElseThrow();
    // then
    assertThat(doc.title()).isEqualTo("이름 안의 공백");
  }

  @Test
  @DisplayName("메모리 문서 제목은 frontmatter name이다")
  void titleFromFrontmatter() {
    // given
    RawItem item =
        file(
            "d/a.md",
            MemoryAdapter.RESOURCE_TYPE,
            "{\"text\":\"---\\nname: no-artifacts\\ntype: feedback\\n---\\n본문 줄\\n\"}");

    // when
    SourceDocument doc = project(item).orElseThrow();

    // then
    assertThat(doc.title()).isEqualTo("no-artifacts");
    assertThat(doc.body()).isEqualTo("---\nname: no-artifacts\ntype: feedback\n---\n본문 줄\n");
  }

  @Test
  @DisplayName("문서 id와 uri는 폴더와 파일 이름으로 정해진다")
  void idAndUriFromPath() {
    // given
    RawItem item = file("d/a.md", MemoryAdapter.RESOURCE_TYPE, "{\"text\":\"본문\"}");

    // when
    SourceDocument doc = project(item).orElseThrow();

    // then
    assertThat(doc.documentId()).isEqualTo("memory/d/a.md");
    assertThat(doc.uri()).isEqualTo("claude-memory://d/a.md");
  }

  @Test
  @DisplayName("생성·갱신 시각은 파일 수정 시각이다")
  void timesAreFileMtime() {
    // given
    RawItem item =
        file(
            "d/a.md",
            MemoryAdapter.RESOURCE_TYPE,
            "{\"text\":\"본문\"}",
            Optional.of("2026-09-01T03:04:05Z"));

    // when
    SourceDocument doc = project(item).orElseThrow();

    // then
    assertThat(doc.createdAt()).isEqualTo("2026-09-01T03:04:05Z");
    assertThat(doc.updatedAt()).isEqualTo("2026-09-01T03:04:05Z");
  }

  @Test
  @DisplayName("수정 시각이 없으면 생성·갱신 시각은 관측 시각이다")
  void timesAreObservedAt() {
    // given
    RawItem item = file("d/a.md", MemoryAdapter.RESOURCE_TYPE, "{\"text\":\"본문\"}");

    // when
    SourceDocument doc = project(item).orElseThrow();

    // then
    assertThat(doc.createdAt()).isEqualTo("2026-10-28T00:00:00Z");
    assertThat(doc.updatedAt()).isEqualTo("2026-10-28T00:00:00Z");
  }

  @Test
  @DisplayName("frontmatter name이 없으면 제목은 파일 이름이다")
  void titleFallsBackToFileName() {
    // given
    RawItem item = file("d/MEMORY.md", MemoryAdapter.RESOURCE_TYPE, "{\"text\":\"- 항목\\n\"}");

    // when
    SourceDocument doc = project(item).orElseThrow();

    // then
    assertThat(doc.title()).isEqualTo("MEMORY.md");
  }

  @Test
  @DisplayName("메모리 파일이 아닌 원본은 문서로 묶지 않는다")
  void ignoresOtherResourceTypes() {
    // given
    RawItem item = file("d/a.md", "claude.transcript.turn", "{\"text\":\"x\"}");

    // when
    var groups = projector.groupIntoDocuments(List.of(item));

    // then
    assertThat(groups).isEmpty();
  }
}
