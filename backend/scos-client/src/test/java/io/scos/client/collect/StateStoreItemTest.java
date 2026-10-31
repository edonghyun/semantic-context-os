package io.scos.client.collect;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("state는 캡처한 원본의 값과 메타데이터를 보존한다(E5)")
class StateStoreItemTest {
  private StateStoreScenario scenario;

  @BeforeEach
  void setUp(@TempDir Path root) {
    scenario = new StateStoreScenario(root);
    scenario.commitMemoryPage();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("저장한 메모리를 읽으면 캡처한 원본과 같다")
  void restoresCapturedItem() {
    // when
    List<RawItem> items = scenario.storedMemoryItems();
    // then
    assertThat(items)
        .containsExactly(
            new RawItem(
                "d/a.md",
                "claude.memory.file",
                "d351793cb4671277790a16177c7a7264d201f9192b9d269cffc83f08b06a1742",
                Optional.empty(),
                Optional.empty(),
                Optional.of("2026-10-27T10:00:00.000Z"),
                Instant.parse("2026-10-28T00:00:00Z"),
                "{\"documentId\":\"memory/d/a.md\"}",
                Optional.of(new RawContent.Inline("application/json", "{\"text\":\"합성 메모\"}"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"documentId\":\"\"}", "malformed"})
  @DisplayName("문서 연결이 없거나 깨졌으면 외부 id를 문서 id로 쓴다")
  void fallsBackToExternalId(String metadata) {
    // given
    scenario.givenMemoryMetadata(metadata);
    // when
    var ids = scenario.documentIds();
    // then
    assertThat(ids).containsExactly("d/a.md");
  }
}
