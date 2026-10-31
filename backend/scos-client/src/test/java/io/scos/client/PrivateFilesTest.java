package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("scos-local 홈과 그 안 파일은 소유자만 읽고 쓴다")
class PrivateFilesTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path root;
  private Path home;
  private ClientPaths paths;

  @BeforeEach
  void setUp() throws IOException {
    home = root.resolve("scos");
    Path projects = root.resolve("claude-projects");
    Path dir = Files.createDirectories(projects.resolve("d"));
    try (var in = getClass().getResourceAsStream("/transcripts/turns.jsonl")) {
      Files.write(dir.resolve("s1.jsonl"), in.readAllBytes());
    }
    paths =
        ClientPaths.from(
            Map.of("SCOS_HOME", home.toString(), "SCOS_CLAUDE_PROJECTS", projects.toString()));
  }

  /** 홈 아래 이름별 권한 문자열(홈 자신은 "."). */
  private Map<String, String> modes(String... names) throws IOException {
    Map<String, String> modes = new TreeMap<>();
    modes.put(".", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)));
    for (String name : names) {
      modes.put(
          name, PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(name))));
    }
    return modes;
  }

  private static Map<String, String> ownerOnly(String... names) {
    Map<String, String> expected = new TreeMap<>();
    expected.put(".", "rwx------");
    for (String name : names) expected.put(name, "rw-------");
    return expected;
  }

  @Test
  @DisplayName("수집한 뒤 홈은 0700, state·색인·-wal·-shm·잠금·질의 로그는 0600이다")
  void ownerOnlyAfterCollect() throws Exception {
    // given
    String[] names = {
      "index.sqlite",
      "index.sqlite-wal",
      "index.sqlite-shm",
      "state.sqlite",
      "state.sqlite-wal",
      "state.sqlite-shm",
      "collect.lock",
      "query-log.jsonl"
    };
    try (ClientAssembly assembly = ClientAssembly.open(paths, CLOCK)) {
      assembly.collectOnce();

      // when: 색인 쓰기 연결을 열어 둔 채로 -wal·-shm이 생기게 한다
      try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + paths.index());
          Statement st = c.createStatement()) {
        st.execute("INSERT INTO space_meta (space_id, key, value) VALUES ('p', 'k', 'v')");
        Map<String, String> modes = modes(names);

        // then
        assertThat(modes).isEqualTo(ownerOnly(names));
      }
    }
  }

  @Test
  @DisplayName("기본 권한으로 이미 있던 홈과 파일은 열 때 소유자 전용으로 좁힌다")
  void tightensExisting() throws Exception {
    // given
    Files.createDirectories(home);
    Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwxr-x"));
    for (String name : new String[] {"query-log.jsonl", "collect.lock"}) {
      Files.writeString(home.resolve(name), "");
      Files.setPosixFilePermissions(
          home.resolve(name), PosixFilePermissions.fromString("rw-r--r--"));
    }

    // when
    Map<String, String> modes;
    try (ClientAssembly assembly = ClientAssembly.open(paths, CLOCK)) {
      assembly.collectOnce();
      modes = modes("query-log.jsonl", "collect.lock", "index.sqlite");
    }

    // then
    assertThat(modes).isEqualTo(ownerOnly("query-log.jsonl", "collect.lock", "index.sqlite"));
  }
}
