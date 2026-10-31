package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("경로는 문서 id 규칙대로 문서 id가 된다(기준선)")
class PathDocumentIdsTest {
  private static final Path P = Path.of("/data/projects");

  @Test
  @DisplayName("트랜스크립트 jsonl은 transcript/<dir>/<session>이다")
  void transcript() {
    // when·then
    assertThat(PathDocumentIds.of(P, Map.of(), P.resolve("d/s1.jsonl")))
        .contains("transcript/d/s1");
  }

  @Test
  @DisplayName("서브에이전트 jsonl은 부모 세션 문서로 센다")
  void subagentMapsToParentSession() {
    // when·then
    assertThat(PathDocumentIds.of(P, Map.of(), P.resolve("d/s1/subagents/a.jsonl")))
        .contains("transcript/d/s1");
  }

  @Test
  @DisplayName("메모리 파일은 memory/<dir>/<name>.md다")
  void memoryFile() {
    // when·then
    assertThat(PathDocumentIds.of(P, Map.of(), P.resolve("d/memory/m.md")))
        .contains("memory/d/m.md");
  }

  @Test
  @DisplayName("문서 루트 아래 파일은 doc/<루트 이름>/<상대 경로>다")
  void documentUnderRoot() {
    // given
    Map<String, Path> roots = Map.of("notes", Path.of("/data/notes"));

    // when·then
    assertThat(PathDocumentIds.of(P, roots, Path.of("/data/notes/a/b.md")))
        .contains("doc/notes/a/b.md");
  }

  @Test
  @DisplayName("표 밖 파일은 비어 있다")
  void unknownFileIsEmpty() {
    // when·then
    assertThat(PathDocumentIds.of(P, Map.of(), P.resolve("d/other.txt"))).isEmpty();
    assertThat(PathDocumentIds.of(P, Map.of(), P.resolve("d/s1/tool-results/x.jsonl"))).isEmpty();
    assertThat(PathDocumentIds.of(P, Map.of(), Path.of("/elsewhere/s1.jsonl"))).isEmpty();
  }
}
