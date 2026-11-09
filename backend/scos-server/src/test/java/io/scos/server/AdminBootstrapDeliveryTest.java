package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.boot.AdminConfiguration;
import io.scos.engine.admin.AdminBootstrap;
import io.scos.engine.admin.CommitFailureStore;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminStore;
import io.scos.store.memory.MemoryAdminScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

@DisplayName("bootstrap 전달 실패는 DB 상태와 보호 파일을 함께 복구한다")
class AdminBootstrapDeliveryTest {
  @TempDir Path directory;

  @Test
  @DisplayName("DB 커밋이 확정 롤백되면 CLI는 새 파일을 지우고 exit1을 반환한다")
  void commitFailureRemovesTokenFile() {
    // given
    var scenario = new DeliveryScenario(directory);
    scenario.givenCommitRollback();
    // when
    var exit = scenario.run();
    // then
    assertThat(exit).isEqualTo(1);
    assertThat(directory.resolve("token")).doesNotExist();
    assertThat(scenario.userCount()).isZero();
  }

  @Test
  @DisplayName("커밋 응답을 잃으면 CLI는 0600 복구 파일과 고정 안내를 남긴다")
  void uncertainCommitRetainsProtectedRecoveryFile() throws Exception {
    // given
    var scenario = new DeliveryScenario(directory);
    scenario.givenLostCommitResponse();
    // when
    var exit = scenario.run();
    // then
    assertThat(exit).isEqualTo(1);
    assertThat(Files.getPosixFilePermissions(directory.resolve("token")))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(scenario.messages)
        .containsExactly(
            "bootstrap: commit outcome requires verification; retain the protected token file and verify database state before retrying.");
  }

  @Test
  @DisplayName("파일 쓰기 실패는 최초 관리자와 토큰을 롤백한다")
  void fileFailureRollsBackBootstrap() {
    // given
    var scenario = new DeliveryScenario(directory);
    // when·then
    assertThatThrownBy(scenario::failFileWrite).isInstanceOf(AdminBootstrap.Failure.class);
    assertThat(scenario.userCount()).isZero();
    assertThat(directory.resolve("token")).doesNotExist();
  }

  @Test
  @DisplayName("전달과 정리가 모두 실패하면 CLI가 보존된 경로를 안내한다")
  void deliveryCleanupFailureReportsRetainedPath() {
    var scenario = new DeliveryScenario(directory);
    // when
    var exit = scenario.runWithCleanupFailure();
    // then
    assertThat(exit).isEqualTo(1);
    assertThat(scenario.messages)
        .anyMatch(
            message ->
                message.startsWith("bootstrap: replacement file retained for recovery at ")
                    && message.contains(directory.toString()));
    assertThat(directory.resolve("token")).doesNotExist();
  }

  @Test
  @DisplayName("정리 이동이 실패하면 CLI는 실패 결과에 앞서 보존 파일 위치를 안내한다")
  void deliveryCleanupMoveFailureReportsRetainedFiles() {
    // given
    var scenario = new DeliveryScenario(directory);
    // when
    var exit = scenario.runWithCleanupMoveFailure();
    // then
    assertThat(exit).isEqualTo(1);
    assertThat(scenario.messages)
        .first()
        .asString()
        .startsWith(
            "bootstrap: retain protected files and verify local file state at "
                + directory.resolve(".scos-bootstrap-"));
    assertThat(directory.resolve("token")).isRegularFile();
  }

  @Test
  @DisplayName("전달과 정리가 모두 실패해도 관리자 생성을 롤백한다")
  void deliveryCleanupFailureStillRollsBackAdministrator() {
    var scenario = new DeliveryScenario(directory);
    // when
    var exit = scenario.runWithCleanupFailure();
    // then
    assertThat(exit).isEqualTo(1);
    assertThat(scenario.userCount()).isZero();
  }

  private static final class DeliveryScenario {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
    private final Path directory;
    private final MemoryAdminScenario storage = new MemoryAdminScenario();
    private final List<String> messages = new ArrayList<>();
    private AdminStore store = storage.store();

    DeliveryScenario(Path directory) {
      this.directory = directory;
    }

    void givenCommitRollback() {
      store = CommitFailureStore.rollbackNext(storage.store());
    }

    void givenLostCommitResponse() {
      store = CommitFailureStore.loseNextResponse(storage.store());
    }

    int run() {
      var command =
          new AdminCommands(AdminConfiguration.newAdminBootstrap(store, CLOCK), messages::add);
      return command.run(
          new CommandArguments(
              new DefaultApplicationArguments(
                  "admin",
                  "bootstrap",
                  "--email=admin@example.com",
                  "--token-file=" + directory.resolve("token"))));
    }

    int runWithCleanupFailure() {
      var file =
          new BootstrapTokenFile(
              directory.resolve("token"),
              (channel, bytes) -> {
                Files.delete(directory.resolve("token"));
                Files.createDirectory(directory.resolve("token"));
                throw new IOException("injected write failure");
              });
      return new AdminCommands(AdminConfiguration.newAdminBootstrap(store, CLOCK), messages::add)
          .execute("admin@example.com", file);
    }

    int runWithCleanupMoveFailure() {
      var file =
          new BootstrapTokenFile(
              directory.resolve("token"),
              (channel, bytes) -> {
                try (var files = Files.list(directory)) {
                  var staging =
                      files
                          .filter(
                              path -> path.getFileName().toString().startsWith(".scos-bootstrap-"))
                          .findFirst()
                          .orElseThrow();
                  Files.createDirectory(staging.resolve("cleanup"));
                }
                throw new IOException("injected write failure");
              });
      return new AdminCommands(AdminConfiguration.newAdminBootstrap(store, CLOCK), messages::add)
          .execute("admin@example.com", file);
    }

    void failFileWrite() {
      var file =
          new BootstrapTokenFile(
              directory.resolve("token"),
              (channel, bytes) -> {
                throw new IOException("injected write failure");
              });
      AdminConfiguration.newAdminBootstrap(store, CLOCK).bootstrap("admin@example.com", file);
    }

    int userCount() {
      return storage.store().transact(session -> session.users(new PageRequest()).items().size());
    }
  }
}
