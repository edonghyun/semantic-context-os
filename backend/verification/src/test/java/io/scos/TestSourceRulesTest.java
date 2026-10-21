package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 바이트코드에 남지 않아 ArchUnit이 볼 수 없는 테스트 소스 규칙(TESTING §13). 소스 파일을 직접 읽는다. 규칙 표본 패키지(scosrules)는
 * ArchUnit 규칙처럼 대상 밖이다.
 */
@DisplayName("테스트 소스는 주석·문자열에 남는 작성 규칙을 지킨다(TESTING §13)")
class TestSourceRulesTest {
  /**
   * 규칙 대상 테스트 소스 루트(모듈마다 `src/test/java`·`src/testFixtures/java`, 그리고 scos-test-support의 main) → 파일
   * 수 하한. 실제로 찾은 루트가 이 목록과 다르면 준비 실패다(빈 디렉터리를 걸어 통과하지 않는다). 하한은 옮기다 빠진 루트를 걸러낸다.
   */
  static final Map<Path, Integer> MIN_FILES =
      Map.ofEntries(
          Map.entry(Path.of("scos-spi/src/test/java"), 2),
          Map.entry(Path.of("scos-spi/src/testFixtures/java"), 7),
          Map.entry(Path.of("scos-engine/src/test/java"), 44),
          Map.entry(Path.of("scos-engine/src/testFixtures/java"), 5),
          Map.entry(Path.of("scos-store-memory/src/test/java"), 5),
          Map.entry(Path.of("scos-store-postgres/src/test/java"), 15),
          Map.entry(Path.of("scos-store-postgres/src/testFixtures/java"), 1),
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
          Map.entry(Path.of("scos-mcp/src/test/java"), 7),
          Map.entry(Path.of("scos-spring-boot-starter/src/test/java"), 3),
          // 공개 내보내기는 사내 overlay 테스트 5개(launchd·롤백·운영 문서)를 빼므로 공개 트리 기준(사내 54)
          Map.entry(Path.of("scos-server/src/test/java"), 49),
          Map.entry(Path.of("scos-test-support/src/main/java"), 16),
          Map.entry(Path.of("scos-test-support/src/test/java"), 6),
          Map.entry(Path.of("verification/src/test/java"), 17));

  static final Set<Path> EXPECTED_ROOTS = MIN_FILES.keySet();

  /** 제품 소스셋(규칙 대상 밖). scos-test-support의 main은 테스트 코드다. */
  static final Set<String> PRODUCT_SOURCE_SETS = Set.of("main", "harness");

  static final String TEST_SUPPORT = "scos-test-support";

  /** 코드 줄의 완전 수식 타입 이름(패키지.…​.Type). import·package·주석 줄과 문자열 안은 제외한다. */
  static final Pattern QUALIFIED_TYPE =
      Pattern.compile(
          "(?<![\\w.\"/])(?:io\\.scos|java|javax|org|com|tools)(?:\\.[a-z]\\w*)+\\.[A-Z]\\w*");

  static final Pattern NOT_CODE = Pattern.compile("^\\s*(import |package |\\*|//|/\\*)");

  /** 옛 전제 모양(C4): `전제:`로 시작하는 문자열과 `전제`로 시작하는 as(…) 설명. 따옴표를 문자 클래스로 써서 이 패턴 자신은 걸리지 않는다. */
  static final Pattern LEGACY_PRECONDITION = Pattern.compile("[\"]전제:|as\\([\"]전제");

  static final Pattern TEST_ANNOTATION = Pattern.compile("@(?:Test|ParameterizedTest)\\b");

  static final Pattern METHOD_NAME = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");

  /** then 구역 표시: `// then`(when은 본문이나 @BeforeEach) 또는 `// when·then`(실행과 검증이 한 식). */
  static final Pattern THEN_COMMENT = Pattern.compile("^//\\s*(?:when·)?then\\b");

  /** 실제 테스트 소스 루트(모듈마다 `src/<소스셋>/java`, 제품 소스셋 제외). 기대 목록·하한과 다르면 준비 실패다. */
  static List<Path> testSourceRoots() throws IOException {
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
    if (!new HashSet<>(roots).equals(new HashSet<>(EXPECTED_ROOTS)))
      throw new IllegalStateException(
          "given 준비 실패: 테스트 소스 루트 " + roots + "가 기대 " + EXPECTED_ROOTS + "와 다르다");
    for (Path root : roots) {
      long count = javaFiles(root).size();
      if (count < MIN_FILES.get(root))
        throw new IllegalStateException(
            "given 준비 실패: " + root + "의 파일 " + count + "개가 하한 " + MIN_FILES.get(root) + "보다 적다");
    }
    return roots;
  }

  static List<Path> javaFiles(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
  }

  /** 규칙 대상 테스트 소스 파일(표본 패키지 scosrules 제외). */
  static List<Path> testSourceFiles() throws IOException {
    List<Path> found = new ArrayList<>();
    for (Path root : testSourceRoots())
      for (Path file : javaFiles(root))
        if (!root.relativize(file).startsWith("scosrules")) found.add(file);
    return found;
  }

  /** 테스트 소스에서 완전 수식 타입 이름을 쓴 코드 줄("파일:줄: 내용"). */
  static List<String> qualifiedTypeNamesInTestSources() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : testSourceFiles()) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++)
        if (isQualifiedTypeInCode(lines.get(i)))
          violations.add(where(file, i + 1) + ": " + lines.get(i).strip());
    }
    return violations;
  }

  static boolean isQualifiedTypeInCode(String line) {
    String code = withoutStrings(line);
    return !NOT_CODE.matcher(code).find() && QUALIFIED_TYPE.matcher(code).find();
  }

  /** 테스트 소스에서 옛 전제 모양을 쓴 줄("파일:줄: 내용"). */
  static List<String> legacyPreconditionsInTestSources() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : testSourceFiles()) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++)
        if (isLegacyPrecondition(lines.get(i)))
          violations.add(where(file, i + 1) + ": " + lines.get(i).strip());
    }
    return violations;
  }

  static boolean isLegacyPrecondition(String line) {
    return LEGACY_PRECONDITION.matcher(line).find();
  }

  /** 테스트 소스에서 then 구역 표시가 없는 테스트 메서드("파일:줄 메서드"). */
  static List<String> testsWithoutThenInTestSources() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : testSourceFiles())
      for (String test : testsWithoutThen(Files.readString(file)))
        violations.add(file + ":" + test);
    return violations;
  }

  /**
   * 소스 하나에서 `@Test`·`@ParameterizedTest` 메서드 중 본문 주석에 then 표시가 없는 것("줄 메서드"). 주석·문자열·문자·텍스트 블록 안의
   * 중괄호와 어노테이션은 코드로 보지 않는다.
   */
  static List<String> testsWithoutThen(String source) {
    JavaSource java = JavaSource.of(source);
    List<String> found = new ArrayList<>();
    Matcher annotation = TEST_ANNOTATION.matcher(java.code);
    while (annotation.find()) {
      Matcher method = METHOD_NAME.matcher(java.code);
      if (!method.find(annotation.end())) continue;
      int open = java.code.indexOf('{', java.closingParen(method.end() - 1));
      int close = java.matchingBrace(open);
      if (!java.commentsBetween(open, close).stream().anyMatch(c -> THEN_COMMENT.matcher(c).find()))
        found.add(java.lineOf(method.start(1)) + " " + method.group(1));
    }
    return found;
  }

  static String where(Path file, int line) {
    return file + ":" + line;
  }

  /** 문자열 리터럴 내용을 비운다(클래스 이름을 문자열로 넘기는 것은 FQCN 사용이 아니다). */
  static String withoutStrings(String line) {
    return line.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
  }

  /** 주석·문자열·문자·텍스트 블록을 공백으로 지운 코드(줄 위치 유지)와 주석 목록. */
  record JavaSource(String text, String code, List<int[]> comments) {
    static JavaSource of(String text) {
      StringBuilder code = new StringBuilder(text);
      List<int[]> comments = new ArrayList<>();
      int i = 0;
      while (i < text.length()) {
        int end;
        if (text.startsWith("//", i)) {
          end = text.indexOf('\n', i);
          if (end < 0) end = text.length();
          comments.add(new int[] {i, end});
        } else if (text.startsWith("/*", i)) {
          end = text.indexOf("*/", i + 2) + 2;
          comments.add(new int[] {i, end});
        } else if (text.startsWith("\"\"\"", i)) {
          end = text.indexOf("\"\"\"", i + 3) + 3;
        } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
          end = i + 1;
          while (text.charAt(end) != text.charAt(i)) end += text.charAt(end) == '\\' ? 2 : 1;
          end++;
        } else {
          i++;
          continue;
        }
        for (int k = i; k < end; k++) if (code.charAt(k) != '\n') code.setCharAt(k, ' ');
        i = end;
      }
      return new JavaSource(text, code.toString(), comments);
    }

    int matchingBrace(int open) {
      int depth = 0;
      for (int i = open; i < code.length(); i++) {
        if (code.charAt(i) == '{') depth++;
        else if (code.charAt(i) == '}' && --depth == 0) return i;
      }
      throw new IllegalStateException("닫히지 않은 중괄호: " + lineOf(open) + "줄");
    }

    int closingParen(int open) {
      int depth = 0;
      for (int i = open; i < code.length(); i++) {
        if (code.charAt(i) == '(') depth++;
        else if (code.charAt(i) == ')' && --depth == 0) return i;
      }
      throw new IllegalStateException("닫히지 않은 괄호: " + lineOf(open) + "줄");
    }

    List<String> commentsBetween(int from, int to) {
      return comments.stream()
          .filter(c -> c[0] > from && c[0] < to)
          .map(c -> text.substring(c[0], c[1]))
          .toList();
    }

    int lineOf(int offset) {
      return (int) text.substring(0, offset).chars().filter(ch -> ch == '\n').count() + 1;
    }
  }

  @Nested
  @DisplayName("완전 수식 타입 이름(본문은 import한 이름을 쓴다)은")
  class QualifiedTypeNames {
    @Test
    @DisplayName("테스트 소스 본문에 없다")
    void noFullyQualifiedTypeNames() throws IOException {
      // when
      List<String> violations = qualifiedTypeNamesInTestSources();

      // then
      assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("규칙은 본문의 완전 수식 이름을 찾는다")
    void findsQualifiedNameInCode() {
      // when
      boolean found = isQualifiedTypeInCode("    new java.util.ArrayList<>();");

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("규칙은 import 줄을 건너뛴다")
    void skipsImports() {
      // when
      boolean found = isQualifiedTypeInCode("import java.util.List;");

      // then
      assertThat(found).isFalse();
    }

    @Test
    @DisplayName("규칙은 문자열 안의 이름을 건너뛴다")
    void skipsStrings() {
      // when
      boolean found = isQualifiedTypeInCode("    run(\"org.springframework.boot.Launcher\");");

      // then
      assertThat(found).isFalse();
    }
  }

  @Nested
  @DisplayName("옛 전제 모양(\"given 준비 실패\"로 옮긴다, C4)은")
  class LegacyPreconditions {
    @Test
    @DisplayName("테스트 소스에 없다")
    void noLegacyPreconditions() throws IOException {
      // when
      List<String> violations = legacyPreconditionsInTestSources();

      // then
      assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("규칙은 as(\"전제…\") 설명을 찾는다")
    void findsAsDescription() {
      // when
      boolean found = isLegacyPrecondition("    assertThat(x).as(\"전제 확인\").isTrue();");

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("규칙은 '전제:'로 시작하는 예외 메시지를 찾는다")
    void findsExceptionMessage() {
      // when
      // 표본을 둘로 나눠 써서 이 파일 자신은 규칙에 걸리지 않는다
      boolean found = isLegacyPrecondition("    throw new IllegalStateException(\"전" + "제: 준비\");");

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("규칙은 \"given 준비 실패\" 메시지를 놓아둔다")
    void passesGivenFailure() {
      // when
      boolean found =
          isLegacyPrecondition("    throw new IllegalStateException(\"given 준비 실패: 준비\");");

      // then
      assertThat(found).isFalse();
    }
  }

  @Nested
  @DisplayName("then 구역 표시(// then 또는 // when·then, C2)는")
  class ThenComments {
    @Test
    @DisplayName("모든 테스트 메서드 본문에 있다")
    void everyTestHasThen() throws IOException {
      // when
      List<String> violations = testsWithoutThenInTestSources();

      // then
      assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("규칙은 when만 있는 테스트를 찾는다")
    void findsWhenOnly() {
      // when
      List<String> found =
          testsWithoutThen(
              """
              class A {
                @Test
                void t() {
                  // when
                  run();
                }
              }
              """);

      // then
      assertThat(found).containsExactly("3 t");
    }

    @Test
    @DisplayName("규칙은 then 주석과 when·then 주석을 받아들인다")
    void acceptsThenAndWhenThen() {
      // when
      List<String> found =
          testsWithoutThen(
              """
              class A {
                @Test
                void a() {
                  // then: 상황의 @BeforeEach가 실행했다
                  check();
                }

                @ParameterizedTest
                @ValueSource(ints = {1, 2})
                void b(int n) {
                  // when·then
                  check(n);
                }
              }
              """);

      // then
      assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("규칙은 문자열 안의 중괄호로 본문 경계를 놓치지 않는다")
    void ignoresBracesInStrings() {
      // when
      List<String> found =
          testsWithoutThen(
              """
              class A {
                @Test
                void a() {
                  // then
                  check("{", '{');
                }

                @Test
                void b() {
                  run("}");
                }
              }
              """);

      // then
      assertThat(found).containsExactly("9 b");
    }

    @Test
    @DisplayName("규칙은 다음 메서드의 주석을 앞 테스트의 것으로 세지 않는다")
    void doesNotBorrowNextMethodComments() {
      // when
      List<String> found =
          testsWithoutThen(
              """
              class A {
                @Test
                void a() {
                  run();
                }

                void helper() {
                  // then
                }
              }
              """);

      // then
      assertThat(found).containsExactly("3 a");
    }
  }
}
