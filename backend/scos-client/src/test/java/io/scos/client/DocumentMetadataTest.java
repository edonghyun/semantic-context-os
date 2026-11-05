package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawItems;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("문서 메타데이터는 연결 id의 JSON 표현을 소유한다(ADR-0018)")
class DocumentMetadataTest {
  @Test
  @DisplayName("문서 id의 따옴표와 역슬래시를 JSON에 이스케이프한다")
  void writesEscapedId() {
    // given
    DocumentMetadata metadata = DocumentMetadata.of("memory/d/a\"b\\c.md");

    // when
    String json = metadata.toJson();

    // then
    assertThat(json).isEqualTo("{\"documentId\":\"memory/d/a\\\"b\\\\c.md\"}");
  }

  @Test
  @DisplayName("다른 메타데이터 필드가 있어도 문서 연결을 읽는다")
  void readsDocumentId() {
    // given
    RawItem item = item("{\"other\":1,\"documentId\":\"transcript/d/s1\"}");

    // when
    Optional<DocumentMetadata> metadata = DocumentMetadata.documentIdOf(item);

    // then
    assertThat(metadata).contains(new DocumentMetadata("transcript/d/s1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"broken", "{}", "[]", "null", "{\"documentId\":\"\"}"})
  @DisplayName("문서 연결이 없거나 JSON이 깨졌으면 빈 값이다")
  void ignoresMissingDocumentId(String json) {
    // given
    RawItem item = item(json);

    // when
    Optional<DocumentMetadata> metadata = DocumentMetadata.documentIdOf(item);

    // then
    assertThat(metadata).isEmpty();
  }

  private RawItem item(String metadata) {
    return RawItems.item("d/s1#0")
        .resourceType("claude.transcript.turn")
        .version("v1")
        .observedAt(Instant.parse("2026-10-28T00:00:00Z"))
        .metadata(metadata)
        .build();
  }
}
