package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.admin.IssuedToken;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("bootstrap 파일은 생성부터 0600이고 자신이 만든 파일만 정리한다")
class BootstrapTokenFileTest {
  @TempDir Path directory;
  private final IssuedToken token =
      new IssuedToken(
          UUID.fromString("00000000-0000-0000-0000-000000000013"),
          "scos_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

  @Test
  @DisplayName("파일은 생성될 때부터 소유자 읽기·쓰기 전용이다")
  void tokenFileIsCreatedWithOwnerReadWriteOnly() throws Exception {
    // when
    var file = new BootstrapTokenFile(directory.resolve("token"));
    file.save(token);
    // then
    assertThat(Files.getPosixFilePermissions(directory.resolve("token")))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  @DisplayName("파일에는 발급 원문과 개행 하나만 있다")
  void tokenFileContainsOnlyIssuedValue() throws Exception {
    // when
    new BootstrapTokenFile(directory.resolve("token")).save(token);
    // then
    assertThat(Files.readString(directory.resolve("token")))
        .isEqualTo("scos_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n");
  }

  @Test
  @DisplayName("기록기가 채널을 닫으면 강제 기록 실패로 공개 파일을 회수한다")
  void closedWriterCannotBypassDurability() {
    var path = directory.resolve("token");
    var file =
        new BootstrapTokenFile(
            path,
            (channel, bytes) -> {
              channel.write(bytes);
              channel.close();
            });
    // when·then
    assertThatThrownBy(() -> file.save(token))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(ClosedChannelException.class);
    assertThat(path).doesNotExist();
  }

  @Test
  @DisplayName("자신이 만든 파일을 회수하고 닫으면 임시 이름도 남지 않는다")
  void removalAlsoCleansStaging() throws Exception {
    // when
    try (var file = new BootstrapTokenFile(directory.resolve("token"))) {
      file.save(token);
      file.remove();
    }
    // then
    try (var entries = Files.list(directory)) {
      assertThat(entries.toList()).isEmpty();
    }
  }

  @Test
  @DisplayName("교체 파일을 보존하고 닫으면 격리한 이름은 남지 않는다")
  void replacementCleanupAlsoCleansStaging() throws Exception {
    var path = directory.resolve("token");
    // when
    try (var file = new BootstrapTokenFile(path)) {
      file.save(token);
      Files.move(path, directory.resolve("original"));
      Files.writeString(path, "replacement");
      file.remove();
    }
    // then
    assertThat(Files.readString(path)).isEqualTo("replacement");
    try (var entries = Files.list(directory)) {
      assertThat(entries.map(entry -> entry.getFileName().toString()).toList())
          .containsExactlyInAnyOrder("original", "token");
    }
  }

  @Test
  @DisplayName("부모가 없는 목적지는 파일을 만들기 전에 거부한다")
  void rootDestinationIsRejected() {
    var file = new BootstrapTokenFile(directory.getRoot());
    // when·then
    assertThatThrownBy(() -> file.save(token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Token file destination unavailable");
  }

  @Test
  @DisplayName("전달 전 복구 안내는 확인할 목적지 경로를 보인다")
  void recoveryBeforeDeliveryNamesDestination() {
    var file = new BootstrapTokenFile(directory.resolve("token"));
    // when
    var notice = file.recoveryNotice();
    // then
    assertThat(notice)
        .isEqualTo(
            "bootstrap: retain protected files and verify local file state at "
                + directory.resolve("token"));
  }

  @Test
  @DisplayName("전달 뒤 복구 안내는 비공개 원본 디렉터리를 보인다")
  void recoveryAfterDeliveryNamesStaging() throws Exception {
    try (var file = new BootstrapTokenFile(directory.resolve("token"))) {
      file.save(token);
      Path staging;
      try (var entries = Files.list(directory)) {
        staging = entries.filter(Files::isDirectory).findFirst().orElseThrow();
      }
      // when
      var notice = file.recoveryNotice();
      // then
      assertThat(notice)
          .isEqualTo("bootstrap: retain protected files and verify local file state at " + staging);
    }
  }

  @Test
  @DisplayName("사전 검사 뒤 생긴 파일은 공개 단계에서 거부하고 보존한다")
  void existingTokenFileIsNotOverwritten() throws Exception {
    // given
    var path = directory.resolve("token");
    var file = new BootstrapTokenFile(path);
    file.verifyDestination();
    Files.writeString(path, "previous-value");
    // when·then
    assertThatThrownBy(() -> file.save(token))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(FileAlreadyExistsException.class);
    file.remove();
    assertThat(Files.readString(path)).isEqualTo("previous-value");
  }

  @Test
  @DisplayName("사전 검사 뒤 생긴 링크는 공개 단계에서 거부하고 보존한다")
  void symbolicLinkTargetIsRejected() throws Exception {
    // given
    var target = directory.resolve("target");
    Files.writeString(target, "previous-value");
    var path = directory.resolve("token");
    var file = new BootstrapTokenFile(path);
    file.verifyDestination();
    Files.createSymbolicLink(path, target);
    // when·then
    assertThatThrownBy(() -> file.save(token))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(FileAlreadyExistsException.class);
    file.remove();
    assertThat(Files.isSymbolicLink(path)).isTrue();
    assertThat(Files.readString(target)).isEqualTo("previous-value");
  }

  @Test
  @DisplayName("확정 실패 뒤 자신이 생성한 파일을 지운다")
  void removesOwnedTokenFile() throws Exception {
    // given
    var file = new BootstrapTokenFile(directory.resolve("token"));
    file.save(token);
    // when
    file.remove();
    // then
    assertThat(directory.resolve("token")).doesNotExist();
  }

  @Test
  @DisplayName("다른 inode로 교체된 파일은 정리하지 않는다")
  void cleanupPreservesReplacementFile() throws Exception {
    // given
    var path = directory.resolve("token");
    var file = new BootstrapTokenFile(path);
    file.save(token);
    Files.move(path, directory.resolve("original"));
    Files.writeString(path, "replacement");
    // when
    file.remove();
    // then
    assertThat(Files.readString(path)).isEqualTo("replacement");
  }

  @Test
  @DisplayName("두 번 정리해도 같은 이름의 새 파일을 지우지 않는다")
  void repeatedCleanupPreservesNewFile() throws Exception {
    // given
    var path = directory.resolve("token");
    var file = new BootstrapTokenFile(path);
    file.save(token);
    file.remove();
    Files.writeString(path, "replacement");
    // when
    file.remove();
    // then
    assertThat(Files.readString(path)).isEqualTo("replacement");
  }

  @Test
  @DisplayName("성공 뒤 임시 원본 이름을 정리해도 최종 토큰 파일은 보존한다")
  void closingDeliveryPreservesPublishedToken() throws Exception {
    // given
    var file = new BootstrapTokenFile(directory.resolve("token"));
    file.save(token);
    // when
    file.close();
    // then
    assertThat(Files.readString(directory.resolve("token")))
        .isEqualTo("scos_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n");
    try (var entries = Files.list(directory)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .containsExactly("token");
    }
  }

  @Test
  @DisplayName("파일 강제 기록 실패는 자신이 만든 불완전 파일을 제거한다")
  void forceFailureRemovesIncompleteFile() throws Exception {
    // given
    var file =
        new BootstrapTokenFile(
            directory.resolve("token"),
            (channel, bytes) -> {
              assertThat(Files.getPosixFilePermissions(directory.resolve("token")))
                  .isEqualTo(PosixFilePermissions.fromString("rw-------"));
              channel.write(bytes);
              throw new IOException("injected force failure");
            });
    // when·then
    assertThatThrownBy(() -> file.save(token)).isInstanceOf(IllegalStateException.class);
    assertThat(directory.resolve("token")).doesNotExist();
  }

  @Test
  @DisplayName("POSIX 권한을 보장할 수 없으면 파일 생성 전에 거부한다")
  void unsupportedPosixPermissionsFailBeforeCreation() throws Exception {
    // given
    var uri = URI.create("jar:" + directory.resolve("files.zip").toUri());
    try (var filesystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
      var path = filesystem.getPath("/token");
      var file = new BootstrapTokenFile(path);
      // when·then
      assertThatThrownBy(file::verifyDestination).isInstanceOf(IllegalStateException.class);
      assertThat(path).doesNotExist();
    }
  }
}
