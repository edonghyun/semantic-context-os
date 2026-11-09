package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("db")
@Tag("process")
@DisplayName("bootstrap CLI는 최초 관리자와 보호 파일을 원자적으로 준비한다")
class AdminBootstrapCliTest {
  @TempDir Path directory;
  private AdminBootstrapScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = new AdminBootstrapScenario(directory);
  }

  @AfterEach
  void tearDown() {
    scenario.close();
  }

  @Test
  @DisplayName("최초 관리자는 인증 가능한 토큰과 두 감사 이벤트와 함께 생성된다")
  void bootstrapCreatesFirstAdminAndTokenAtomically() throws Exception {
    // when
    var file = directory.resolve("token");
    var result = scenario.bootstrap(file);
    // then
    assertThat(result.exitCode()).isZero();

    assertThat(scenario.authenticates(file)).isTrue();
    assertThat(scenario.auditActions()).containsExactly("user.create", "token.issue");
  }

  @Test
  @DisplayName("최초 bootstrap은 사용자와 토큰을 각각 하나만 만든다")
  void bootstrapCreatesExactlyOneUserAndToken() throws Exception {
    // when
    var result = scenario.bootstrap(directory.resolve("token"));
    // then
    assertThat(result.exitCode()).isZero();
    assertThat(scenario.administrators()).isEqualTo(1);
    assertThat(scenario.count("user_tokens")).isEqualTo(1);
  }

  @Test
  @DisplayName("비활성 관리자도 재실행을 막는다")
  void existingInactiveAdminPreventsBootstrap() throws Exception {
    // given
    scenario.givenInactiveAdministrator();
    // when
    var result = scenario.bootstrap(directory.resolve("token"));
    // then
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(scenario.count("users")).isEqualTo(1);
    assertThat(scenario.count("user_tokens")).isZero();
  }

  @Test
  @DisplayName("두 실제 프로세스가 경합해도 한 관리자와 한 파일만 생성된다")
  void concurrentBootstrapCreatesOneAdmin() throws Exception {
    // when
    var firstFile = directory.resolve("first-token");
    var secondFile = directory.resolve("second-token");
    var first = scenario.start(firstFile, "first@example.com");
    var second = scenario.start(secondFile, "second@example.com");
    var exits = List.of(scenario.finish(first).exitCode(), scenario.finish(second).exitCode());
    // then
    assertThat(exits).containsExactlyInAnyOrder(0, 1);
    assertThat(scenario.administrators()).isEqualTo(1);
    assertThat(List.of(firstFile, secondFile).stream().filter(Files::exists)).hasSize(1);
  }

  @Test
  @DisplayName("감사 저장 실패는 모든 변경을 롤백한다")
  void bootstrapAuditFailureRollsBackAllWrites() throws Exception {
    // given
    scenario.givenAuditFailure();
    // when
    scenario.bootstrap(directory.resolve("token"));
    // then
    assertThat(scenario.count("users")).isZero();
    assertThat(scenario.count("user_tokens")).isZero();
    assertThat(scenario.count("audit_events")).isZero();
  }

  @Test
  @DisplayName("원문은 stdout·stderr·감사에 노출되지 않는다")
  void bootstrapNeverWritesSecretToStdoutOrStderr() throws Exception {
    // when
    var file = directory.resolve("token");
    var result = scenario.bootstrap(file);
    // then
    var secret = Files.readString(file).strip();
    assertThat(result.stdout()).doesNotContain(secret).doesNotContain("scos_");
    assertThat(result.stderr()).doesNotContain(secret).doesNotContain("scos_");
    assertThat(scenario.auditText()).doesNotContain(secret).doesNotContain("scos_");
  }

  @Test
  @DisplayName("기존 파일을 덮어쓰지 않고 DB도 바꾸지 않는다")
  void existingTokenFileIsNotOverwritten() throws Exception {
    // given
    var file = directory.resolve("token");
    Files.writeString(file, "previous-value");
    // when
    var result = scenario.bootstrap(file);
    // then
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(Files.readString(file)).isEqualTo("previous-value");
    assertThat(scenario.count("users")).isZero();
  }

  @Test
  @DisplayName("심볼릭 링크는 파일과 DB를 바꾸지 않고 거부한다")
  void symbolicLinkTargetIsRejected() throws Exception {
    // given
    var target = directory.resolve("target");
    Files.writeString(target, "previous-value");
    var link = Files.createSymbolicLink(directory.resolve("token"), target);
    // when
    var result = scenario.bootstrap(link);
    // then
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(Files.readString(target)).isEqualTo("previous-value");
    assertThat(scenario.count("users")).isZero();
  }

  @Test
  @DisplayName("부모가 없으면 DB 변경 전에 실패한다")
  void missingParentFailsBeforeDatabaseWrite() throws Exception {
    // when
    var result = scenario.bootstrap(directory.resolve("missing/token"));
    // then
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(scenario.count("users")).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("공백과 등호 두 옵션 표기는 독립 DB에서 모두 성공한다")
  void bothCliOptionSpellingsWork(boolean equals) throws Exception {
    // when
    var file = directory.resolve("token");
    var result = scenario.bootstrap(file, equals);
    // then
    assertThat(result.exitCode()).isZero();
    assertThat(scenario.email()).isEqualTo("admin@example.com");
    assertThat(scenario.authenticates(file)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "unknown", "invalid-email", "duplicate", "extra"})
  @DisplayName("잘못된 bootstrap 사용법은 exit2이며 DB를 바꾸지 않는다")
  void usageErrorsExitTwo(String kind) throws Exception {
    // when
    var result = scenario.usageError(kind);
    // then
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(scenario.count("users")).isZero();
  }
}
