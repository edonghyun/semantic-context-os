package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import scosrules.ConventionSamples;

/**
 * 코딩 규약 규칙 자체의 계약(TESTING §13.2): 술어가 위반 표본(scosrules.ConventionSamples$Violates…)을 잡고 통과 표본을
 * 놓아두는지, 고정 저장소가 새 위반을 받아들이지 않게 설정됐는지 본다.
 */
@DisplayName("코딩 규약 규칙은 위반 표본을 잡고 새 위반을 고정하지 않는다")
class ConventionRulesSelfTest {
  static final JavaClasses SAMPLES =
      new ClassFileImporter().importClasses(ConventionSamples.class.getDeclaredClasses());

  static final Path STORE = Path.of("verification/src/test/resources/frozen");

  /**
   * 고정 위반 수의 상한(2026-10-26 재고정: 61 → 25, TESTING §13.2). 저장소 전체(로깅 규칙의 고정 목록 포함)를 센다. 줄이기만 한다 — 고정
   * 목록이 늘면 이 숫자도 바꿔야 해서 diff에 드러난다.
   */
  static final int FROZEN_CEILING = 21;

  /** 규칙을 표본에 돌린 위반 설명들. */
  static List<String> violations(ArchRule rule) {
    return rule.evaluate(SAMPLES).getFailureReport().getDetails();
  }

  static long lineCount(Path file) {
    try (var lines = Files.lines(file)) {
      return lines.filter(l -> !l.isBlank()).count();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  @DisplayName("public static 메서드 규칙은")
  class Factories {
    @Test
    @DisplayName("자기 타입이 아닌 값을 돌려주는 정적 함수를 잡는다")
    void catchesStaticFunctions() {
      // when
      List<String> found = violations(ConventionRulesTest.PUBLIC_STATIC_METHODS_ARE_FACTORIES);

      // then
      assertThat(found)
          .anyMatch(v -> v.contains("ViolatesStaticFunction"))
          .anyMatch(v -> v.contains("ViolatesListOfSelf"))
          .anyMatch(v -> v.contains("ViolatesOptionalOfOther"));
    }

    @Test
    @DisplayName("인스턴스를 만들 수 있는 클래스나 JDK 인터페이스를 돌려주는 조립 함수를 잡는다")
    void catchesAssemblyOutsideFactoryClasses() {
      // when
      List<String> found = violations(ConventionRulesTest.PUBLIC_STATIC_METHODS_ARE_FACTORIES);

      // then
      assertThat(found)
          .anyMatch(v -> v.contains("ViolatesAssemblyFromInstantiable"))
          .anyMatch(v -> v.contains("ViolatesJdkInterfaceFromFactoryClass"));
    }

    @Test
    @DisplayName("자기 타입·하위 타입·Optional 팩토리, 조립 팩토리, 순수 계산 모듈 함수와 main은 놓아둔다")
    void passesFactories() {
      // when
      List<String> found = violations(ConventionRulesTest.PUBLIC_STATIC_METHODS_ARE_FACTORIES);

      // then
      assertThat(found).noneMatch(v -> v.contains("Passes"));
    }
  }

  @Nested
  @DisplayName("순수 계산 모듈 구조 규칙은")
  class PureFunctionModules {
    @Test
    @DisplayName("상태가 있거나 계약을 받거나 인스턴스를 만들 수 있는 선언을 잡는다")
    void catchesStatefulDeclarations() {
      // when
      List<String> found = violations(ConventionRulesTest.pureFunctionModulesAreStateless);

      // then
      assertThat(found)
          .anyMatch(v -> v.contains("$ViolatesPureWithState "))
          .anyMatch(v -> v.contains("$ViolatesPureTakingContract "))
          .anyMatch(v -> v.contains("$ViolatesPureInstantiable "));
    }

    @Test
    @DisplayName("구조를 지키는 선언은 놓아둔다")
    void passesStatelessDeclarations() {
      // when
      List<String> found = violations(ConventionRulesTest.pureFunctionModulesAreStateless);

      // then
      assertThat(found).noneMatch(v -> v.contains("Passes"));
    }
  }

  @Nested
  @DisplayName("유틸 이름 규칙은")
  class UtilityNames {
    @Test
    @DisplayName("Utils·Util·Helper로 끝나는 클래스를 잡는다")
    void catchesUtilityNames() {
      // when
      List<String> found = violations(ConventionRulesTest.NO_UTILITY_NAMES);

      // then
      assertThat(found)
          .anyMatch(v -> v.contains("$ViolatesNamingUtils>"))
          .anyMatch(v -> v.contains("$ViolatesNamingUtil>"))
          .anyMatch(v -> v.contains("$ViolatesNamingHelper>"));
    }

    @Test
    @DisplayName("다른 이름의 클래스는 놓아둔다")
    void passesOtherNames() {
      // when
      List<String> found = violations(ConventionRulesTest.NO_UTILITY_NAMES);

      // then
      assertThat(found).noneMatch(v -> v.contains("Passes"));
    }
  }

  @Nested
  @DisplayName("고정 저장소는")
  class Store {
    @Test
    @DisplayName("refreeze·저장소 생성·갱신을 모두 끈다")
    void refusesNewViolationsByConfiguration() throws IOException {
      // given
      Properties properties = new Properties();
      try (InputStream in =
          ConventionRulesSelfTest.class.getResourceAsStream("/archunit.properties")) {
        properties.load(in);
      }

      // then
      assertThat(properties)
          .containsEntry("freeze.refreeze", "false")
          .containsEntry("freeze.store.default.allowStoreCreation", "false")
          .containsEntry("freeze.store.default.allowStoreUpdate", "false");
    }

    @Test
    @DisplayName("정한 규칙만 담는다(설명을 바꿔 새로 고정하지 못한다)")
    void holdsOnlyKnownRules() throws IOException {
      // given
      Properties rules = new Properties();
      try (Reader in = Files.newBufferedReader(STORE.resolve("stored.rules"))) {
        rules.load(in);
      }

      // then
      assertThat(rules.stringPropertyNames())
          .containsExactlyInAnyOrder(
              ConventionRulesTest.PUBLIC_STATIC_METHODS_ARE_FACTORIES.getDescription(),
              ConventionRulesTest.NO_UTILITY_NAMES.getDescription(),
              LoggingRulesTest.NO_DIRECT_WARN_OR_ERROR.getDescription());
    }

    @Test
    @DisplayName("고정된 위반 수는 상한을 넘지 않는다")
    void staysUnderCeiling() throws IOException {
      // given
      long frozen;
      try (var files = Files.list(STORE)) {
        frozen =
            files
                .filter(f -> !f.getFileName().toString().equals("stored.rules"))
                .mapToLong(ConventionRulesSelfTest::lineCount)
                .sum();
      }

      // then
      assertThat(frozen).isLessThanOrEqualTo(FROZEN_CEILING);
    }

    @Test
    @DisplayName("고정된 규칙은 저장소에 없는 새 위반을 실패로 보고한다")
    void reportsNewViolationsThroughFrozenRule() {
      // given
      ImportOption productAndSamples =
          location ->
              new TestLocations.ProductMainCode().includes(location)
                  || location.contains("/scosrules/ConventionSamples");
      JavaClasses classes =
          new ClassFileImporter()
              .withImportOption(productAndSamples)
              .importPackages("io.scos", "scosrules");

      // when
      List<String> found =
          ConventionRulesTest.publicStaticMethodsAreFactories
              .evaluate(classes)
              .getFailureReport()
              .getDetails();

      // then
      assertThat(found)
          .isNotEmpty()
          .allMatch(v -> v.contains("scosrules.ConventionSamples$Violates"));
    }
  }
}
