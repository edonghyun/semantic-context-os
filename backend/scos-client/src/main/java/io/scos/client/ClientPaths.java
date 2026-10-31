package io.scos.client;

import io.scos.spi.store.SpaceId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * scos-local의 파일 위치. 홈 = `SCOS_HOME` 또는 `~/.scos`, 원천 루트 = `SCOS_CLAUDE_PROJECTS` 또는
 * `~/.claude/projects`, 질의 로그 = `SCOS_QUERY_LOG`(설정됐는데 비었으면 끔, 없으면 `<홈>/query-log.jsonl`). 세 환경변수는
 * 절대 경로여야 한다 — 상대 경로는 세션마다 다른 작업 폴더에 풀려 홈이 갈라지므로 {@link IllegalArgumentException}으로 거부한다.
 */
public record ClientPaths(Path home, Path claudeProjects, Optional<Path> queryLog) {
  /** 이 기기의 모든 원천이 들어가는 공간. */
  public static final SpaceId SPACE = new SpaceId("local");

  /** 사용자 홈은 {@code user.home}. */
  public static ClientPaths from(Map<String, String> env) {
    return from(env, Path.of(System.getProperty("user.home")));
  }

  public static ClientPaths from(Map<String, String> env, Path userHome) {
    Path home = pathOr(env, "SCOS_HOME", userHome.resolve(".scos"));
    Path projects = pathOr(env, "SCOS_CLAUDE_PROJECTS", userHome.resolve(".claude/projects"));
    String log = env.get("SCOS_QUERY_LOG");
    Optional<Path> queryLog =
        log == null
            ? Optional.of(home.resolve("query-log.jsonl"))
            : log.isBlank() ? Optional.empty() : Optional.of(absolute("SCOS_QUERY_LOG", log));
    return new ClientPaths(home, projects, queryLog);
  }

  private static Path pathOr(Map<String, String> env, String name, Path fallback) {
    @Nullable String value = env.get(name);
    return value == null || value.isBlank() ? fallback : absolute(name, value);
  }

  private static Path absolute(String name, String value) {
    Path path = Path.of(value);
    if (!path.isAbsolute()) throw new IllegalArgumentException(name + "는 절대 경로여야 한다: " + value);
    return path;
  }

  public Path state() {
    return home.resolve("state.sqlite");
  }

  public Path index() {
    return home.resolve("index.sqlite");
  }

  public Path lock() {
    return home.resolve("collect.lock");
  }
}
