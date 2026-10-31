package io.scos.client.collect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 기기당 수집 잠금. 프로세스가 죽으면 OS가 풀어서 남은 쪽이 다음 주기에 잡는다. */
public final class CollectLock implements AutoCloseable {
  /**
   * 이 JVM이 쥔 잠금 파일. Linux의 FileChannel 잠금은 POSIX fcntl 잠금이라 같은 파일의 어떤 fd든 닫으면 프로세스의 잠금이 모두 풀린다. 그래서
   * 중복 시도는 파일을 열기 전에 여기서 막는다.
   */
  private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private final Path key;
  private final FileChannel channel;
  private final FileLock lock;
  private boolean closed;

  private CollectLock(Path key, FileChannel channel, FileLock lock) {
    this.key = key;
    this.channel = channel;
    this.lock = lock;
  }

  /** 잠금을 잡으면 그 손잡이, 다른 프로세스나 같은 JVM의 다른 보유자가 있으면 empty. 잠금 파일은 이 클래스 밖에서 열지 않는다(열고 닫으면 잠금이 풀린다). */
  public static Optional<CollectLock> tryAcquire(Path lockFile) {
    Path key = lockFile.toAbsolutePath().normalize();
    if (!HELD.add(key)) return Optional.empty();
    FileChannel channel;
    try {
      Path parent = key.getParent();
      if (parent != null) Files.createDirectories(parent);
      channel = open(key);
    } catch (IOException e) {
      HELD.remove(key);
      throw new UncheckedIOException(e);
    } catch (RuntimeException e) {
      HELD.remove(key);
      throw e;
    }
    try {
      FileLock lock = channel.tryLock();
      if (lock != null) return Optional.of(new CollectLock(key, channel, lock));
    } catch (IOException e) {
      release(key, channel);
      throw new UncheckedIOException(e);
    } catch (RuntimeException e) {
      release(key, channel);
      throw e;
    }
    release(key, channel);
    return Optional.empty();
  }

  /**
   * 잠금 파일을 소유자 전용(0600)으로 만들거나 연다. 이미 있던 파일은 열린 채로 권한만 좁힌다(chmod는 fd를 열지 않아 잠금에 닿지 않는다). POSIX가 아니면
   * 권한은 건너뛴다.
   */
  private static FileChannel open(Path key) throws IOException {
    Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    if (!key.getFileSystem().supportedFileAttributeViews().contains("posix"))
      return FileChannel.open(key, options);
    FileChannel channel =
        FileChannel.open(key, options, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    try {
      Files.setPosixFilePermissions(key, OWNER_ONLY);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
    return channel;
  }

  private static void release(Path key, FileChannel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      // 이미 실패·미획득 경로다
    } finally {
      HELD.remove(key);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      lock.release();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      release(key, channel);
    }
  }
}
