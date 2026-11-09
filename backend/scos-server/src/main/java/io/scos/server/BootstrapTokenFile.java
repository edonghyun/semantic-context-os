package io.scos.server;

import io.scos.engine.admin.AdminBootstrap.TokenDelivery;
import io.scos.engine.admin.IssuedToken;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * 비공개 디렉터리에서 CREATE_NEW·NOFOLLOW_LINKS로 만든 0600 inode를 새 이름으로만 공개한다. 부모 디렉터리는 운영자 관리 경계다. 정리할 이름은
 * 먼저 격리하여 교체된 inode를 삭제하지 않는다.
 */
final class BootstrapTokenFile implements TokenDelivery, AutoCloseable {
  private final Path path;
  private final ContentWriter writer;
  private Optional<Path> staging = Optional.empty();
  private Optional<Object> createdKey = Optional.empty();
  private Optional<Path> replacementRecovery = Optional.empty();
  private boolean published;

  @FunctionalInterface
  interface ContentWriter {
    void write(FileChannel channel, ByteBuffer bytes) throws IOException;
  }

  BootstrapTokenFile(Path path) {
    this(path, BootstrapTokenFile::writeFully);
  }

  BootstrapTokenFile(Path path, ContentWriter writer) {
    this.path = path.toAbsolutePath();
    this.writer = writer;
  }

  /** 실제 공개 시점에도 원자적 hard link 생성으로 기존 이름·심볼릭 링크를 거부한다. */
  void verifyDestination() {
    verifyParent();
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Token file destination unavailable");
  }

  private void verifyParent() {
    var parent = path.getParent();
    if (parent == null
        || !Files.isDirectory(parent)
        || Files.getFileAttributeView(parent, PosixFileAttributeView.class) == null)
      throw new IllegalStateException("Token file destination unavailable");
  }

  @Override
  public void save(IssuedToken token) {
    verifyParent();
    try {
      var directory =
          Files.createTempDirectory(
              path.getParent(),
              ".scos-bootstrap-",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      staging = Optional.of(directory);
      write(directory.resolve("token"), token);
    } catch (IOException | UnsupportedOperationException failure) {
      remove();
      throw new IllegalStateException("Token file delivery failed", failure);
    }
  }

  private void write(Path anchor, IssuedToken token) throws IOException {
    try (var channel =
        FileChannel.open(
            anchor,
            Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
      createdKey = Optional.ofNullable(attributes(anchor).fileKey());
      if (createdKey.isEmpty()) throw new IOException("File identity unavailable");
      Files.createLink(path, anchor);
      published = true;
      writer.write(channel, StandardCharsets.UTF_8.encode(token.value() + "\n"));
      channel.force(true);
    }
  }

  /** 이름을 원자적으로 격리한 뒤 inode를 판정한다. 재실행은 나중에 생긴 파일을 건드리지 않는다. */
  void remove() {
    if (replacementRecovery.isPresent())
      throw new IllegalStateException("Token file cleanup requires recovery");
    if (!published) return;
    var quarantine = staging.orElseThrow().resolve("cleanup");
    try {
      Files.move(path, quarantine, StandardCopyOption.ATOMIC_MOVE);
      if (createdKey.orElseThrow().equals(attributes(quarantine).fileKey()))
        Files.delete(quarantine);
      else restoreReplacement(quarantine);
      published = false;
    } catch (NoSuchFileException absent) {
      published = false;
      // 공개 이름이 이미 이동되었어도 소유하지 않은 이름은 삭제하지 않는다.
    } catch (IOException failure) {
      throw new IllegalStateException("Token file cleanup failed", failure);
    }
  }

  private void restoreReplacement(Path quarantine) throws IOException {
    replacementRecovery = Optional.of(quarantine);
    Files.createLink(path, quarantine);
    Files.delete(quarantine);
    replacementRecovery = Optional.empty();
  }

  /** 성공·불확실 커밋 모두 최종 파일을 보존하고, 비공개 원본 이름만 정리한다. */
  @Override
  public void close() {
    if (staging.isEmpty()) return;
    var directory = staging.get();
    try {
      Files.deleteIfExists(directory.resolve("token"));
      if (replacementRecovery.isEmpty()) Files.delete(directory);
      staging = Optional.empty();
    } catch (IOException failure) {
      throw new IllegalStateException("Token file staging cleanup failed", failure);
    }
  }

  String recoveryNotice() {
    return replacementRecovery
        .map(location -> "bootstrap: replacement file retained for recovery at " + location)
        .orElseGet(
            () ->
                "bootstrap: retain protected files and verify local file state at "
                    + staging.map(Path::toString).orElse(path.toString()));
  }

  private BasicFileAttributes attributes(Path file) throws IOException {
    return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
    while (bytes.hasRemaining()) channel.write(bytes);
  }
}
