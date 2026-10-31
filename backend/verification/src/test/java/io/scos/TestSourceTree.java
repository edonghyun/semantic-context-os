package io.scos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 규칙 대상 테스트 소스 트리: 모듈마다 `src/<소스셋>/java`(제품 소스셋 main·harness 제외, scos-test-support의 main은 테스트 코드다).
 * 찾은 루트가 기대 목록과 다르거나 파일 수가 하한보다 적으면 준비 실패다 — 빈 디렉터리를 걸어 통과하지 않고, 옮기다 빠진 루트를 걸러낸다. 규칙 표본
 * 패키지(scosrules)는 대상 밖이다.
 */
final class TestSourceTree {
  /** 루트 → 파일 수 하한. */
  static final Map<Path, Integer> MIN_FILES =
      Map.ofEntries(
          Map.entry(Path.of("scos-spi/src/test/java"), 2),
          Map.entry(Path.of("scos-spi/src/testFixtures/java"), 7),
          Map.entry(Path.of("scos-engine/src/test/java"), 44),
          Map.entry(Path.of("scos-engine/src/testFixtures/java"), 5),
          Map.entry(Path.of("scos-store-memory/src/test/java"), 5),
          Map.entry(Path.of("scos-store-memory/src/testFixtures/java"), 1),
          Map.entry(Path.of("scos-store-postgres/src/test/java"), 15),
          Map.entry(Path.of("scos-store-postgres/src/testFixtures/java"), 1),
          Map.entry(Path.of("scos-store-sqlite/src/test/java"), 12),
          Map.entry(Path.of("scos-client/src/test/java"), 14),
          Map.entry(Path.of("scos-collection/src/test/java"), 109),
          Map.entry(Path.of("scos-collection/src/testFixtures/java"), 20),
          Map.entry(Path.of("scos-connector-slack/src/test/java"), 6),
          Map.entry(Path.of("scos-connector-slack/src/testFixtures/java"), 2),
          Map.entry(Path.of("scos-connector-notion/src/test/java"), 16),
          Map.entry(Path.of("scos-connector-notion/src/testFixtures/java"), 6),
          Map.entry(Path.of("scos-connector-jira/src/test/java"), 20),
          Map.entry(Path.of("scos-connector-jira/src/testFixtures/java"), 4),
          Map.entry(Path.of("scos-connector-google-drive/src/test/java"), 24),
          Map.entry(Path.of("scos-connector-google-drive/src/testFixtures/java"), 7),
          Map.entry(Path.of("scos-connector-tiro/src/test/java"), 10),
          Map.entry(Path.of("scos-connector-tiro/src/testFixtures/java"), 3),
          Map.entry(Path.of("scos-provider-gemini/src/test/java"), 3),
          Map.entry(Path.of("scos-provider-openai-compatible/src/test/java"), 3),
          Map.entry(Path.of("scos-mcp/src/test/java"), 7),
          Map.entry(Path.of("scos-spring-boot-starter/src/test/java"), 3),
          // 공개 내보내기는 사내 overlay 테스트 5개(launchd·롤백·운영 문서)를 빼므로 공개 트리 기준(사내 54)
          Map.entry(Path.of("scos-server/src/test/java"), 49),
          Map.entry(Path.of("scos-test-support/src/main/java"), 16),
          Map.entry(Path.of("scos-test-support/src/test/java"), 6),
          Map.entry(Path.of("verification/src/test/java"), 17));

  private static final Set<String> PRODUCT_SOURCE_SETS = Set.of("main", "harness");
  private static final String TEST_SUPPORT = "scos-test-support";

  private final List<Path> files;

  private TestSourceTree(List<Path> files) {
    this.files = files;
  }

  /** 작업 디렉터리(backend) 아래의 트리. */
  static TestSourceTree current() throws IOException {
    List<Path> found = new ArrayList<>();
    for (Path root : roots())
      for (Path file : javaFiles(root))
        if (!root.relativize(file).startsWith("scosrules")) found.add(file);
    return new TestSourceTree(found);
  }

  List<Path> files() {
    return files;
  }

  /** 규칙을 어긴 줄("파일:줄: 내용"). */
  List<String> linesViolating(Predicate<String> rule) throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : files) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++)
        if (rule.test(lines.get(i)))
          violations.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
    }
    return violations;
  }

  private static List<Path> roots() throws IOException {
    List<Path> roots = new ArrayList<>();
    try (Stream<Path> modules = Files.list(Path.of("."))) {
      for (Path module : modules.map(Path::normalize).sorted().toList()) {
        Path src = module.resolve("src");
        if (!Files.isDirectory(src)) continue;
        try (Stream<Path> sourceSets = Files.list(src)) {
          sourceSets
              .filter(
                  d ->
                      module.toString().equals(TEST_SUPPORT)
                          || !PRODUCT_SOURCE_SETS.contains(d.getFileName().toString()))
              .map(d -> d.resolve("java"))
              .filter(Files::isDirectory)
              .forEach(roots::add);
        }
      }
    }
    roots.sort(null);
    if (!new HashSet<>(roots).equals(MIN_FILES.keySet()))
      throw new IllegalStateException(
          "given 준비 실패: 테스트 소스 루트 " + roots + "가 기대 " + MIN_FILES.keySet() + "와 다르다");
    for (Path root : roots) {
      long count = javaFiles(root).size();
      if (count < MIN_FILES.get(root))
        throw new IllegalStateException(
            "given 준비 실패: " + root + "의 파일 " + count + "개가 하한 " + MIN_FILES.get(root) + "보다 적다");
    }
    return roots;
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
  }
}
