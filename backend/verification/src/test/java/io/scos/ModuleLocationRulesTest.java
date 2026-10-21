package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모듈 위치 규칙(단계 4 T6): 제품 패키지는 정해진 모듈 하나에만 있다 — 제품 코드에 분할 패키지가 없고, 클래스를 다른 모듈로 옮기면 걸린다. 모듈 사이 방향은
 * Gradle 의존 선언과 루트의 checkModuleGraph가 강제한다.
 */
@DisplayName("제품 패키지는 정해진 모듈에 있다(단계 4)")
class ModuleLocationRulesTest {
  /** 패키지 접두어 → 모듈. 클래스는 가장 긴 접두어의 모듈에 속한다. */
  static final Map<String, String> MODULE_OF_PACKAGE =
      Map.ofEntries(
          Map.entry("io.scos.spi", "scos-spi"),
          Map.entry("io.scos.engine", "scos-engine"),
          Map.entry("io.scos.store.memory", "scos-store-memory"),
          Map.entry("io.scos.store.postgres", "scos-store-postgres"),
          Map.entry("io.scos.collection", "scos-collection"),
          Map.entry("io.scos.connector.slack", "scos-connector-slack"),
          Map.entry("io.scos.connector.notion", "scos-connector-notion"),
          Map.entry("io.scos.connector.jira", "scos-connector-jira"),
          Map.entry("io.scos.connector.googledrive", "scos-connector-google-drive"),
          Map.entry("io.scos.connector.tiro", "scos-connector-tiro"),
          Map.entry("io.scos.provider.gemini", "scos-provider-gemini"),
          Map.entry("io.scos.mcp", "scos-mcp"),
          Map.entry("io.scos.boot", "scos-spring-boot-starter"),
          Map.entry("io.scos.server", "scos-server"),
          Map.entry("io.scos.harness", "scos-server"));

  /** 제품 모듈 14개 → main 최상위 클래스 수 하한(모듈 표의 main 파일 수, server는 하네스 소스셋을 빼고 센다). */
  static final Map<String, Integer> MIN_CLASSES =
      Map.ofEntries(
          Map.entry("scos-spi", 48),
          Map.entry("scos-engine", 66),
          Map.entry("scos-store-memory", 5),
          Map.entry("scos-store-postgres", 7),
          Map.entry("scos-collection", 70),
          Map.entry("scos-connector-slack", 9),
          Map.entry("scos-connector-notion", 17),
          Map.entry("scos-connector-jira", 11),
          Map.entry("scos-connector-google-drive", 27),
          Map.entry("scos-connector-tiro", 9),
          Map.entry("scos-provider-gemini", 3),
          Map.entry("scos-mcp", 7),
          Map.entry("scos-spring-boot-starter", 16),
          Map.entry("scos-server", 17));

  /**
   * 제품 jar(`…/scos-engine.jar!/…`) 이름이나 클래스 디렉터리(`…/scos-engine/build/classes/java/main/…`)의 모듈.
   */
  static final Pattern JAR_MODULE = Pattern.compile("/([a-z-]+)\\.jar!/");

  static final Pattern DIR_MODULE = Pattern.compile("/([a-z-]+)/build/classes/java/");

  @Test
  @DisplayName("제품 클래스는 패키지가 정한 모듈 jar에 있다")
  void productClassesLiveInTheModuleTheirPackageNames() {
    // given
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new TestLocations.ProductCode())
            .importPackages("io.scos");
    Map<String, Integer> topLevelByModule = new TreeMap<>();
    for (JavaClass type : classes)
      if (type.isTopLevelClass()) topLevelByModule.merge(moduleOf(type), 1, Integer::sum);
    if (!topLevelByModule.keySet().equals(MIN_CLASSES.keySet()))
      throw new IllegalStateException(
          "given 준비 실패: 관측한 모듈 "
              + topLevelByModule.keySet()
              + "가 제품 모듈 "
              + new TreeMap<>(MIN_CLASSES).keySet()
              + "와 다르다");
    topLevelByModule.forEach(
        (module, count) -> {
          if (count < MIN_CLASSES.get(module))
            throw new IllegalStateException(
                "given 준비 실패: "
                    + module
                    + "의 최상위 클래스 "
                    + count
                    + "개가 하한 "
                    + MIN_CLASSES.get(module)
                    + "보다 적다");
        });

    // when
    List<String> misplaced = new ArrayList<>();
    for (JavaClass type : classes) {
      String expected = expectedModule(type.getPackageName());
      String actual = moduleOf(type);
      if (!actual.equals(expected))
        misplaced.add(type.getName() + ": " + actual + " (기대 " + expected + ")");
    }

    // then
    assertThat(misplaced).isEmpty();
  }

  /** 클래스 파일 위치의 모듈 이름. jar도 클래스 디렉터리도 아니면 준비 실패다. */
  static String moduleOf(JavaClass type) {
    String uri =
        type.getSource()
            .orElseThrow(
                () -> new IllegalStateException("given 준비 실패: " + type.getName() + "의 위치가 없다"))
            .getUri()
            .toString();
    for (Pattern pattern : List.of(JAR_MODULE, DIR_MODULE)) {
      Matcher matcher = pattern.matcher(uri);
      if (matcher.find()) return matcher.group(1);
    }
    throw new IllegalStateException(
        "given 준비 실패: " + type.getName() + "의 위치 " + uri + "에서 모듈을 찾지 못했다");
  }

  /** 가장 긴 접두어의 모듈. 표에 없는 패키지는 "(표에 없음)"이라 어느 모듈과도 맞지 않는다. */
  static String expectedModule(String packageName) {
    String best = null;
    for (String prefix : MODULE_OF_PACKAGE.keySet())
      if ((packageName.equals(prefix) || packageName.startsWith(prefix + "."))
          && (best == null || prefix.length() > best.length())) best = prefix;
    return best == null ? "(표에 없음: " + packageName + ")" : MODULE_OF_PACKAGE.get(best);
  }
}
