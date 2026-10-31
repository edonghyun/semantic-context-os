package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scosrules.LoggingSamples;

/**
 * 로깅 규칙 자체의 계약: 규칙이 위반 표본(scosrules.LoggingSamples$Violates…)을 잡고 통과 표본을 놓아두는지, 고정된 규칙이 저장소에 없는 새
 * 위반을 실패로 보고하는지 본다.
 */
@DisplayName("로깅 규칙은 SLF4J warn·error 직접 호출을 잡고 새 위반을 고정하지 않는다")
class LoggingRulesSelfTest {
  static final JavaClasses SAMPLES =
      new ClassFileImporter().importClasses(LoggingSamples.class.getDeclaredClasses());

  static List<String> violations() {
    return LoggingRulesTest.NO_DIRECT_WARN_OR_ERROR
        .evaluate(SAMPLES)
        .getFailureReport()
        .getDetails();
  }

  @Test
  @DisplayName("warn·error 직접 호출을 잡는다")
  void catchesWarnAndError() {
    // when
    List<String> found = violations();

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("$ViolatesWarn.run()"))
        .anyMatch(v -> v.contains("$ViolatesError.run("));
  }

  @Test
  @DisplayName("atWarn·atError·atLevel fluent 호출을 잡는다")
  void catchesFluentCalls() {
    // when
    List<String> found = violations();

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("$ViolatesAtWarn.run()"))
        .anyMatch(v -> v.contains("$ViolatesAtError.run()"))
        .anyMatch(v -> v.contains("$ViolatesAtLevel.run()"));
  }

  @Test
  @DisplayName("warn 메서드 참조를 잡는다")
  void catchesMethodReferences() {
    // when
    List<String> found = violations();

    // then
    assertThat(found).anyMatch(v -> v.contains("$ViolatesMethodRef.run("));
  }

  @Test
  @DisplayName("makeLoggingEventBuilder·LocationAwareLogger.log 호출을 잡는다")
  void catchesLevelAsValueEntryPoints() {
    // when
    List<String> found = violations();

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("$ViolatesMakeBuilder.run()"))
        .anyMatch(v -> v.contains("$ViolatesLocationAwareLog.run("));
  }

  @Test
  @DisplayName("Logger를 넓힌 타입으로 부른 warn도 잡는다")
  void catchesSubtypeCalls() {
    // when
    List<String> found = violations();

    // then
    assertThat(found).anyMatch(v -> v.contains("$ViolatesWarnThroughSubtype.run("));
  }

  @Test
  @DisplayName("info·debug 평문과 EventLog 호출은 놓아둔다")
  void passesInfoDebugAndEventLog() {
    // when
    List<String> found = violations();

    // then
    assertThat(found).noneMatch(v -> v.contains("Passes"));
  }

  @Test
  @DisplayName("고정된 규칙은 저장소에 없는 새 위반을 실패로 보고한다")
  void reportsNewViolationsThroughFrozenRule() {
    // given
    ImportOption productAndSamples =
        location ->
            new TestLocations.ProductMainCode().includes(location)
                || location.contains("/scosrules/LoggingSamples");
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(productAndSamples)
            .importPackages("io.scos", "scosrules");

    // when
    List<String> found =
        LoggingRulesTest.noDirectWarnOrError.evaluate(classes).getFailureReport().getDetails();

    // then
    assertThat(found).isNotEmpty().allMatch(v -> v.contains("scosrules.LoggingSamples$Violates"));
  }
}
