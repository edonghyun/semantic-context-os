package io.scos.client.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Claude 프로젝트의 파일 배치와 안정된 외부 id를 소유한다. 하위 에이전트 디렉터리는 탐색하지 않는다. */
public final class ClaudeProjects {
  private final Path root;

  public ClaudeProjects(Path root) {
    this.root = root;
  }

  public List<Path> sessionFiles() {
    return files("", ".jsonl");
  }

  public List<Path> memoryFiles() {
    return files("memory", ".md");
  }

  public String key(Path file) {
    return root.relativize(file).getName(0) + "/" + file.getFileName();
  }

  private List<Path> files(String subdirectory, String suffix) {
    if (!Files.isDirectory(root)) return List.of();
    List<Path> result = new ArrayList<>();
    try (Stream<Path> projects = Files.list(root)) {
      for (Path project : projects.filter(Files::isDirectory).sorted().toList()) {
        Path directory = project.resolve(subdirectory);
        if (!subdirectory.isEmpty() && !Files.isDirectory(directory)) continue;
        try (Stream<Path> entries = Files.list(directory)) {
          entries
              .filter(p -> p.getFileName().toString().endsWith(suffix))
              .filter(Files::isRegularFile)
              .sorted()
              .forEach(result::add);
        } catch (NoSuchFileException e) {
          // 나열 사이 사라진 폴더만 건너뛴다.
        } catch (IOException e) {
          // 메모리의 읽기 실패를 무시하면 기존 파일이 삭제로 나가므로 원천 실패로 전파한다.
          if (!subdirectory.isEmpty()) throw e;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return result;
  }
}
