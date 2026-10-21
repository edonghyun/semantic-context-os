package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 래퍼의 stdout 계약(리뷰 B I2, 실제 JDK·bootJar·DB): 맞지 않는(깨진) CDS 아카이브로 띄워도 stdout 첫 줄이 JSON-RPC 응답이다.
 * JDK 통합 로그의 경고 기본 출력은 stdout이라 래퍼가 막지 않으면 "[warning][cds] …" 줄이 응답 앞에 붙는다.
 */
@Tag("db")
@Tag("process")
@DisplayName("MCP 래퍼의 stdout에는 JSON-RPC만 나간다(JDK 경고는 stderr로)")
class McpCdsProcessTest {
  static final Path JAR =
      Path.of(System.getProperty("scos.boot.jar", "build/libs/scos-backend.jar")).toAbsolutePath();
  static final String JAVA =
      Path.of(System.getProperty("scos.java.home", System.getProperty("java.home")), "bin", "java")
          .toString();
  static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("깨진 CDS 아카이브로 MCP 래퍼를 띄우면")
  class BrokenArchive {
    final ScriptHarness wrapper = new ScriptHarness();
    boolean exitedAfterEof;
    int exitCode;
    String firstStdoutLine;
    String stderr;

    @BeforeAll
    void launchWithBrokenArchive() throws Exception {
      wrapper.env.put("SCOS_JAVA", JAVA);
      wrapper.env.put("SCOS_JAR", JAR.toString());
      ScriptHarness.write(
          wrapper.home.resolve(".env"),
          "SCOS_DB_URL="
              + PostgresSupport.jdbcUrl()
              + "\nSCOS_DB_USER="
              + PostgresSupport.username()
              + "\nSCOS_DB_PASSWORD="
              + PostgresSupport.password()
              + "\nGEMINI_API_KEY=unused-in-test\n");
      ScriptHarness.setPermissions(wrapper.home.resolve(".env"), "rw-------");
      Path archive = Files.createDirectories(wrapper.home.resolve("cds")).resolve("scos-mcp.jsa");
      ScriptHarness.write(archive, "not a CDS archive");
      Process p = wrapper.start("scos-mcp.sh");
      try (OutputStream stdin = p.getOutputStream()) {
        stdin.write((INITIALIZE + "\n").getBytes(StandardCharsets.UTF_8));
      }
      exitedAfterEof = p.waitFor(ScriptHarness.PROCESS_LIMIT.toSeconds() * 4, TimeUnit.SECONDS);
      if (!exitedAfterEof) p.destroyForcibly().waitFor();
      exitCode = p.exitValue();
      stderr = wrapper.read(wrapper.records.resolve("scos-mcp.sh.err"));
      firstStdoutLine =
          wrapper
              .read(wrapper.records.resolve("scos-mcp.sh.out"))
              .lines()
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("given 준비 실패: stdout이 비었다\n" + stderr));
    }

    @AfterAll
    void close() {
      wrapper.close();
    }

    @Test
    @DisplayName("stdout 첫 줄은 initialize에 대한 JSON-RPC 응답이다")
    void firstLineIsJsonRpc() throws IOException {
      // then
      assertThat(new ObjectMapper().readTree(firstStdoutLine).path("id").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("stdin이 닫히면 프로세스는 종료 코드 0으로 끝난다")
    void exitsAfterStdinEof() {
      // then
      assertThat(exitedAfterEof).as("stdin EOF 뒤 끝났다").isTrue();
      assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("CDS 경고는 stderr로 간다")
    void cdsWarningOnStderr() {
      // then
      assertThat(stderr).contains("[warning][cds]");
    }
  }
}
