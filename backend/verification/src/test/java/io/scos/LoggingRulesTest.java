package io.scos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;

/**
 * 로깅 규칙(logging.md §6): 운영 코드의 warn·error는 {@code EventLog}로만 쓴다. 대상은 제품 main 클래스(harness 소스셋·
 * scos-test-support·testFixtures 제외). 기존 위반은 ConventionRulesTest와 같은 고정 저장소({@code
 * src/test/resources/frozen})에 고정한다 — 새 위반은 실패하고, 고친 위반은 저장소 갱신을 요구한다.
 */
@AnalyzeClasses(packages = "io.scos", importOptions = TestLocations.ProductMainCode.class)
@DisplayName("운영 코드는 warn·error를 EventLog로만 쓴다(logging.md §6)")
class LoggingRulesTest {
  /** SLF4J를 직접 부를 수 있는 유일한 클래스. */
  static final String EVENT_LOG = "io.scos.engine.EventLog";

  /**
   * 막는 Logger 메서드: warn·error와 그 fluent 진입점. 수준을 값으로 받는 진입점(atLevel·makeLoggingEventBuilder,
   * LocationAwareLogger 등의 log)은 정적으로 수준을 가릴 수 없어 통째로 막는다.
   */
  static final Set<String> WARN_OR_ERROR =
      Set.of("warn", "error", "atWarn", "atError", "atLevel", "makeLoggingEventBuilder", "log");

  /** 고정하기 전의 규칙(자체 테스트가 표본에 돌린다). 설명 문장은 고정 저장소의 열쇠다 — 바꾸면 저장소를 다시 만들어야 한다. */
  static final ArchRule NO_DIRECT_WARN_OR_ERROR =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(EVENT_LOG)
          .should()
          .accessTargetWhere(warnOrErrorOnSlf4jLogger())
          .as(
              "운영 코드는 SLF4J Logger의 warn·error·atWarn·atError·atLevel·makeLoggingEventBuilder·log를"
                  + " 호출하거나 메서드 참조로 넘기지 않는다(EventLog로만 쓴다)");

  @ArchTest
  static final ArchRule noDirectWarnOrError = FreezingArchRule.freeze(NO_DIRECT_WARN_OR_ERROR);

  /** 호출과 메서드 참조({@code log::warn})를 함께 본다 — 둘 다 코드 단위 접근이다. 필드 접근은 대상이 아니다. */
  private static DescribedPredicate<JavaAccess<?>> warnOrErrorOnSlf4jLogger() {
    return DescribedPredicate.describe(
        "SLF4J Logger." + WARN_OR_ERROR,
        access ->
            access instanceof JavaCodeUnitAccess<?>
                && WARN_OR_ERROR.contains(access.getName())
                && access.getTargetOwner().isAssignableTo(Logger.class));
  }
}
