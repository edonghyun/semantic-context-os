package io.scos.harness.evaluation.baseline;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 파일 경로 → 문서 id (계획 Part A 문서 id 규칙). 트랜스크립트·서브에이전트·자동 메모리는 projects 아래, 문서는 --docs 루트 아래다. 표 밖 파일은
 * empty.
 */
public final class PathDocumentIds {
  private PathDocumentIds() {}

  public static Optional<String> of(Path projects, Map<String, Path> docRoots, Path file) {
    Path normalized = file.toAbsolutePath().normalize();
    Path base = projects.toAbsolutePath().normalize();
    if (normalized.startsWith(base)) {
      Optional<String> id = inProjects(base.relativize(normalized));
      if (id.isPresent()) return id;
    }
    for (Map.Entry<String, Path> root : docRoots.entrySet()) {
      Path rootPath = root.getValue().toAbsolutePath().normalize();
      if (normalized.startsWith(rootPath) && !normalized.equals(rootPath))
        return Optional.of("doc/" + root.getKey() + "/" + slashed(rootPath.relativize(normalized)));
    }
    return Optional.empty();
  }

  /** projects 기준 상대 경로: d/s.jsonl, d/s/subagents/x.jsonl, d/memory/n.md. */
  private static Optional<String> inProjects(Path relative) {
    int n = relative.getNameCount();
    String dir = relative.getName(0).toString();
    String last = relative.getFileName().toString();
    if (n == 2 && last.endsWith(".jsonl"))
      return Optional.of("transcript/" + dir + "/" + stem(last));
    if (n == 4 && relative.getName(2).toString().equals("subagents") && last.endsWith(".jsonl"))
      return Optional.of("transcript/" + dir + "/" + relative.getName(1));
    if (n == 3 && relative.getName(1).toString().equals("memory") && last.endsWith(".md"))
      return Optional.of("memory/" + dir + "/" + last);
    return Optional.empty();
  }

  private static String stem(String fileName) {
    return fileName.substring(0, fileName.lastIndexOf('.'));
  }

  private static String slashed(Path relative) {
    return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
  }
}
