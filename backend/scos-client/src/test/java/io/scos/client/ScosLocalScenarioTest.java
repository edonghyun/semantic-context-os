package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("로컬 시나리오는 운영 배선으로 합성 문서를 수집하고 검색한다(D2, E2)")
class ScosLocalScenarioTest {
  @Test
  @DisplayName("세션을 수집하면 매칭한 턴 제목으로 검색된다")
  void searchesSession(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenSession();

      // when
      scenario.collect();

      // then
      assertThat(scenario.search("대체는 별로"))
          .extracting(hit -> hit.title())
          .containsExactly("턴 2 · 2026-10-27T10:00:06Z");
    }
  }

  @Test
  @DisplayName("메모리를 수집하면 문서 식별자로 검색된다")
  void searchesMemory(@TempDir Path root) throws Exception {
    // given
    try (var scenario = new ScosLocalScenario(root)) {
      scenario.givenMemory();

      // when
      scenario.collect();

      // then
      assertThat(scenario.ids("보라색 고양이")).containsExactly("memory/d/a.md");
    }
  }
}
