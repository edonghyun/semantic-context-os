package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MCP 래퍼(deploy/java/bin/scos-mcp.sh)의 계약 — TS mcp.sh와 같다: .env를 읽고 SCOS_QUERY_LOG를 켠다. 질의 로그에는 질의
 * 원문이 들어가므로 래퍼가 디렉터리 700·파일 600으로 만든다(Java는 기본 umask, ADR-0023 §3). CDS 아카이브가 있으면 쓴다(ADR-0030).
 */
@Tag("process")
@DisplayName("MCP 래퍼 scos-mcp.sh는 TS mcp.sh와 같게 질의 로그를 켠다(ADR-0023 §3)")
class McpWrapperTest {
  static final String SCRIPT = "scos-mcp.sh";

  final ScriptHarness wrapper = new ScriptHarness().fakeJava();

  @AfterEach
  void close() {
    wrapper.close();
  }

  Path queryLog() {
    return wrapper.home.resolve("logs").resolve("mcp-queries.jsonl");
  }

  @Nested
  @DisplayName("권한 600 .env로 실행하면")
  class WithEnvFile {
    @BeforeEach
    void givenEnvFile() {
      wrapper.withEnvFile();
    }

    @Test
    @DisplayName("질의 로그를 logs/mcp-queries.jsonl로 켠다")
    void enablesQueryLog() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.read(wrapper.records.resolve("java.env")))
          .contains("SCOS_QUERY_LOG=" + queryLog());
    }

    @Test
    @DisplayName("질의 로그 파일을 600으로 만든다")
    void queryLogFileIs600() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(ScriptHarness.permissions(queryLog())).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("로그 디렉터리를 700으로 만든다")
    void logDirIs700() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(ScriptHarness.permissions(queryLog().getParent())).isEqualTo("rwx------");
    }

    @Test
    @DisplayName("이미 있던 질의 로그도 600으로 고친다")
    void tightensExistingQueryLog() throws Exception {
      // given
      Files.createDirectories(queryLog().getParent());
      ScriptHarness.write(queryLog(), "{}\n");
      ScriptHarness.setPermissions(queryLog(), "rw-r--r--");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(ScriptHarness.permissions(queryLog())).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("CDS 아카이브가 있으면 -XX:SharedArchiveFile로 띄운다")
    void usesCdsArchive() throws Exception {
      // given
      Path archive = wrapper.home.resolve("cds").resolve("scos-mcp.jsa");
      Files.createDirectories(archive.getParent());
      ScriptHarness.write(archive, "archive");

      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.read(wrapper.records.resolve("java.args")).lines())
          .contains("-XX:SharedArchiveFile=" + archive);
    }

    @Test
    @DisplayName("CDS 아카이브가 없으면 아카이브 옵션 없이 java -jar <JAR> mcp로 띄운다")
    void launchesWithoutArchive() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.read(wrapper.records.resolve("java.args")).lines())
          .noneMatch(arg -> arg.startsWith("-XX:SharedArchiveFile"))
          .containsSubsequence("-jar", wrapper.env.get("SCOS_JAR"), "mcp");
    }

    @Test
    @DisplayName("JVM 통합 로그를 stdout에서 끄고 경고를 stderr로 보낸다")
    void jvmWarningsToStderr() {
      // when
      wrapper.run(SCRIPT);

      // then
      assertThat(wrapper.read(wrapper.records.resolve("java.args")).lines())
          .containsSubsequence("-Xlog:disable", "-Xlog:all=warning:stderr", "-jar");
    }

    @Nested
    @DisplayName("로그 디렉터리가 이미 남에게 열린 채(755) 있으면")
    class OpenExistingLogDir {
      @BeforeEach
      void givenOpenLogDir() throws Exception {
        Files.createDirectories(queryLog().getParent());
        ScriptHarness.setPermissions(queryLog().getParent(), "rwxr-xr-x");
      }

      @Test
      @DisplayName("그 권한을 바꾸지 않는다")
      void keepsExistingDirectoryMode() {
        // when
        wrapper.run(SCRIPT);

        // then
        assertThat(ScriptHarness.permissions(queryLog().getParent())).isEqualTo("rwxr-xr-x");
      }

      @Test
      @DisplayName("경고한다")
      void warnsOnOpenExistingDirectory() {
        // when
        var result = wrapper.run(SCRIPT);

        // then
        assertThat(result.stderr()).contains("\"event\":\"wrapper.log_dir.permissions\"");
      }
    }

    @Nested
    @DisplayName("SCOS_QUERY_LOG를 빈 값으로 주면(o2 비교 재생 — 질의 원문을 디스크에 남기지 않는다)")
    class QueryLogDisabled {
      @BeforeEach
      void givenEmptyQueryLog() {
        wrapper.env.put("SCOS_QUERY_LOG", "");
      }

      @Test
      @DisplayName("java에 빈 값을 넘겨 질의 로그를 끈다")
      void passesEmpty() {
        // when
        wrapper.run(SCRIPT);

        // then
        assertThat(wrapper.read(wrapper.records.resolve("java.env")).lines())
            .contains("SCOS_QUERY_LOG=");
      }

      @Test
      @DisplayName(".env에 질의 로그 경로가 있어도 호출자의 빈 값이 이긴다")
      void callerBeatsEnvFile() {
        // given
        ScriptHarness.write(
            wrapper.home.resolve(".env"),
            "SCOS_QUERY_LOG=" + wrapper.home.resolve("from-env.jsonl") + "\n");
        ScriptHarness.setPermissions(wrapper.home.resolve(".env"), "rw-------");

        // when
        wrapper.run(SCRIPT);

        // then
        assertThat(wrapper.read(wrapper.records.resolve("java.env")).lines())
            .contains("SCOS_QUERY_LOG=");
      }

      @Test
      @DisplayName("질의 로그 파일을 만들지 않는다")
      void createsNoFile() {
        // when
        wrapper.run(SCRIPT);

        // then
        assertThat(queryLog()).doesNotExist();
      }
    }
  }
}
