package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("scos-local 경로는 환경변수에서 정한다")
class ClientPathsTest {
  private static final Path USER_HOME = Path.of("/u");

  @Test
  @DisplayName("환경변수가 없으면 ~/.scos와 ~/.claude/projects를 쓴다")
  void defaultsUnderUserHome() {
    // given
    Map<String, String> env = Map.of();

    // when
    ClientPaths paths = ClientPaths.from(env, USER_HOME);

    // then
    assertThat(paths.index()).isEqualTo(Path.of("/u/.scos/index.sqlite"));
    assertThat(paths.claudeProjects()).isEqualTo(Path.of("/u/.claude/projects"));
    assertThat(paths.queryLog()).contains(Path.of("/u/.scos/query-log.jsonl"));
  }

  @Test
  @DisplayName("SCOS_HOME 아래에 state·색인·잠금 파일을 둔다")
  void filesUnderScosHome() {
    // given
    Map<String, String> env = Map.of("SCOS_HOME", "/h");

    // when
    ClientPaths paths = ClientPaths.from(env, USER_HOME);

    // then
    assertThat(paths.state()).isEqualTo(Path.of("/h/state.sqlite"));
    assertThat(paths.index()).isEqualTo(Path.of("/h/index.sqlite"));
    assertThat(paths.lock()).isEqualTo(Path.of("/h/collect.lock"));
  }

  @Test
  @DisplayName("SCOS_QUERY_LOG가 비어 있으면 질의 로그를 끈다")
  void emptyQueryLogDisables() {
    // given
    Map<String, String> env = Map.of("SCOS_QUERY_LOG", "");

    // when
    ClientPaths paths = ClientPaths.from(env, USER_HOME);

    // then
    assertThat(paths.queryLog()).isEmpty();
  }

  @Test
  @DisplayName("SCOS_QUERY_LOG·SCOS_CLAUDE_PROJECTS 값을 그대로 쓴다")
  void explicitPaths() {
    // given
    Map<String, String> env = Map.of("SCOS_QUERY_LOG", "/q.jsonl", "SCOS_CLAUDE_PROJECTS", "/p");

    // when
    ClientPaths paths = ClientPaths.from(env, USER_HOME);

    // then
    assertThat(paths.queryLog()).contains(Path.of("/q.jsonl"));
    assertThat(paths.claudeProjects()).isEqualTo(Path.of("/p"));
  }

  @ParameterizedTest
  @DisplayName("경로 환경변수가 상대 경로면 세션 작업 폴더에 풀지 않고 거부한다")
  @ValueSource(strings = {"SCOS_HOME", "SCOS_QUERY_LOG", "SCOS_CLAUDE_PROJECTS"})
  void rejectsRelativePaths(String name) {
    // given
    Map<String, String> env = Map.of(name, "rel/path");

    // when
    var resolving = assertThatThrownBy(() -> ClientPaths.from(env, USER_HOME));

    // then
    resolving.isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
  }
}
