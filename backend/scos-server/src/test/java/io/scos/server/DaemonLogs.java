package io.scos.server;

import io.scos.support.ChildJvm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 데몬의 콘솔·사람·기계 로그를 파일 배치와 무관한 스트림으로 읽는다. */
final class DaemonLogs {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path console;
  private final Path directory;

  DaemonLogs(Path console, Path directory) {
    this.console = console;
    this.directory = directory;
  }

  String console() {
    return read(console);
  }

  String human() {
    return readStream("log");
  }

  List<JsonNode> events() {
    return readStream("jsonl").lines().filter(line -> !line.isBlank()).map(this::event).toList();
  }

  private String readStream(String extension) {
    try (var files = Files.list(directory)) {
      return files
          .filter(
              file ->
                  file.getFileName()
                      .toString()
                      .matches("serve(?:\\.\\d{4}-\\d{2}-\\d{2})?\\." + extension))
          .sorted()
          .map(DaemonLogs::read)
          .collect(Collectors.joining());
    } catch (NoSuchFileException missing) {
      return "";
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private JsonNode event(String line) {
    try {
      return JSON.readTree(line);
    } catch (RuntimeException failure) {
      throw new AssertionError("JSON이 아닌 로그 줄: " + line, failure);
    }
  }

  private static String read(Path file) {
    try {
      return ChildJvm.readLog(file);
    } catch (NoSuchFileException missing) {
      // 회전의 이름 변경과 읽기가 겹치면 다음 관찰에서 새 파일 이름을 읽는다.
      return "";
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
