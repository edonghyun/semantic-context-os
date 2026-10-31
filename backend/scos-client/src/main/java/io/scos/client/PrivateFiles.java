package io.scos.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * scos-local 홈과 그 안 파일을 소유자 전용(폴더 0700, 파일 0600)으로 둔다 — 트랜스크립트 사본과 질의 로그가 들어 있고, 원본 트랜스크립트도 0600이다.
 * SQLite·잠금이 파일을 만들기 전에 권한을 정해 만들어 두고, 이미 있으면 권한을 좁힌다. SQLite는 -wal·-shm을 본 파일 권한으로 만든다. POSIX 권한이
 * 없는 파일 시스템이면 권한은 건너뛴다.
 */
final class PrivateFiles {
  static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
  static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
  private static final String[] SQLITE_SIDE_FILES = {"-wal", "-shm"};

  private PrivateFiles() {}

  /** 폴더를 0700으로 만든다(위 폴더는 기본 권한). 이미 있으면 0700으로 좁힌다. */
  static void directory(Path dir) {
    try {
      if (!posix(dir)) {
        Files.createDirectories(dir);
        return;
      }
      Path parent = dir.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      try {
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIRECTORY));
      } catch (FileAlreadyExistsException e) {
        if (!Files.isDirectory(dir)) throw e;
      }
      // 만들 때 umask가 깎은 비트와 예전에 기본 권한으로 만든 폴더를 함께 맞춘다
      Files.setPosixFilePermissions(dir, DIRECTORY);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 파일을 0600으로 만들어 둔다. 이미 있으면 0600으로 좁힌다. 위 폴더가 없으면 아무것도 하지 않는다(만들 쪽이 실패를 다룬다). */
  static void file(Path file) {
    Path parent = file.toAbsolutePath().getParent();
    if (parent == null || !Files.isDirectory(parent) || !posix(parent)) return;
    try {
      try {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE));
      } catch (FileAlreadyExistsException e) {
        // 이미 있다: 보통 파일만 아래에서 좁힌다(SCOS_QUERY_LOG=/dev/null 같은 장치는 건드리지 않는다)
        if (!Files.isRegularFile(file)) return;
      }
      Files.setPosixFilePermissions(file, FILE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** SQLite 파일을 0600으로 만들어 두고, 남아 있는 -wal·-shm도 0600으로 좁힌다. */
  static void database(Path file) {
    file(file);
    if (!posix(file.toAbsolutePath())) return;
    for (String suffix : SQLITE_SIDE_FILES) {
      Path side = file.resolveSibling(file.getFileName() + suffix);
      try {
        // 없는 쪽 파일은 만들지 않는다(SQLite가 만든다)
        Files.setPosixFilePermissions(side, FILE);
      } catch (NoSuchFileException e) {
        // 아직 없다
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }
}
