package io.scos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 규칙 대상 제품 main 소스 트리: 제품 모듈({@link ProductModules})마다 `src/main/java`(harness
 * 소스셋·scos-test-support 제외). 찾은 루트가 제품 모듈 목록과 다르면 준비 실패다 — 새 모듈이 규칙 밖에 남지 않게 한다.
 */
final class ProductSourceTree {
  private ProductSourceTree() {}

  /** 작업 디렉터리(backend) 아래 제품 main 소스 파일. */
  static List<Path> files() throws IOException {
    Set<String> expected = new TreeSet<>(ProductModules.MODULE_OF_PACKAGE.values());
    Set<String> found = new TreeSet<>();
    try (Stream<Path> modules = Files.list(Path.of("."))) {
      modules
          .map(m -> m.getFileName().toString())
          .filter(m -> m.startsWith("scos-") && !m.equals("scos-test-support"))
          .filter(m -> Files.isDirectory(Path.of(m, "src/main/java")))
          .forEach(found::add);
    }
    if (!found.equals(expected))
      throw new IllegalStateException(
          "given 준비 실패: 제품 main 소스 루트 " + found + "가 제품 모듈 " + expected + "와 다르다");
    List<Path> files = new ArrayList<>();
    for (String module : found)
      try (Stream<Path> walk = Files.walk(Path.of(module, "src/main/java"))) {
        walk.filter(f -> f.toString().endsWith(".java")).sorted().forEach(files::add);
      }
    return files;
  }
}
