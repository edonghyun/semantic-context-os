package io.scos.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * harness 테스트 코드 규칙 자체의 계약(F15 T15.2, tr-vocab-harness High 9): 위반 표본(harnessrules.Violates…)은 잡고
 * 통과 표본(Passes…)은 놓아둔다. backend TestCodeRulesSelfTest와 같은 표본·같은 기대라 두 단언 셈 사본이 갈라지면 한쪽이 실패한다.
 */
@DisplayName("harness 테스트 코드 규칙은 위반 표본을 잡고 통과 표본은 놓아둔다(TESTING §13)")
class TestCodeRulesSelfTest {
  static final JavaClasses SAMPLES = new ClassFileImporter().importPackages("harnessrules");

  static List<String> violations(ArchRule rule) {
    return rule.allowEmptyShould(true).evaluate(SAMPLES).getFailureReport().getDetails();
  }

  @Nested
  @DisplayName("단언 셈(테스트 하나에 단언 진입점 셋까지)은")
  class AssertionCount {
    @Test
    @DisplayName("단언 넷인 테스트를 잡는다")
    void catchesFour() {
      // when
      List<String> found = violations(TestCodeRulesTest.testMethodsCheckOneContract);

      // then
      assertThat(found).anyMatch(v -> v.contains("ViolatesFourAssertions"));
    }

    @Test
    @DisplayName("도우미 메서드 안의 단언도 센다")
    void countsHelperAssertions() {
      // when
      List<String> found = violations(TestCodeRulesTest.testMethodsCheckOneContract);

      // then
      assertThat(found).anyMatch(v -> v.contains("ViolatesAssertionsInHelper"));
    }

    @Test
    @DisplayName("생성자 안의 단언도 센다")
    void countsConstructorAssertions() {
      // when
      List<String> found = violations(TestCodeRulesTest.testMethodsCheckOneContract);

      // then
      assertThat(found).anyMatch(v -> v.contains("ViolatesAssertionsInConstructor"));
    }

    @Test
    @DisplayName("단언 셋인 테스트와 assert… 도우미 셋은 통과시킨다")
    void passesWithinLimit() {
      // when
      List<String> found = violations(TestCodeRulesTest.testMethodsCheckOneContract);

      // then
      assertThat(found)
          .noneMatch(v -> v.contains("PassesThreeAssertions") || v.contains("PassesAssertHelper"));
    }
  }

  @Test
  @DisplayName("Thread.sleep과 TimeUnit.sleep을 모두 잡는다")
  void catchesSleeps() {
    // when
    List<String> found = violations(TestCodeRulesTest.noThreadSleep);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesThreadSleep"))
        .anyMatch(v -> v.contains("ViolatesTimeUnitSleep"));
  }

  @Test
  @DisplayName("@Test에 @DisplayName이 없으면 잡고 있으면 놓아둔다")
  void catchesMissingDisplayName() {
    // when
    List<String> found = violations(TestCodeRulesTest.testMethodsHaveDisplayName);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesMissingDisplayName"))
        .noneMatch(v -> v.contains("PassesDisplayName"));
  }

  @Test
  @DisplayName("클래스 @DisplayName이 없는 테스트 클래스를 잡는다")
  void catchesMissingClassDisplayName() {
    // when
    List<String> found = violations(TestCodeRulesTest.testClassesHaveDisplayName);

    // then
    assertThat(found).anyMatch(v -> v.contains("ViolatesClassDisplayName "));
  }

  @Test
  @DisplayName("상속한 테스트만 가진 구현 클래스의 제목 누락도 잡는다")
  void catchesInheritedOnlyClass() {
    // when
    List<String> found = violations(TestCodeRulesTest.testClassesHaveDisplayName);

    // then
    assertThat(found).anyMatch(v -> v.contains("ViolatesInheritedClassDisplayName "));
  }

  @Test
  @DisplayName("제목을 단 구현 클래스와 중첩 표본의 바깥 클래스는 놓아둔다")
  void passesTitledClasses() {
    // when
    List<String> found = violations(TestCodeRulesTest.testClassesHaveDisplayName);

    // then
    assertThat(found)
        .noneMatch(v -> v.contains("PassesInheritedClassDisplayName") || v.contains("RuleSamples"));
  }

  @Test
  @DisplayName("60자를 넘는 테스트 제목을 잡고 짧은 제목은 놓아둔다")
  void catchesLongDisplayName() {
    // when
    List<String> found = violations(TestCodeRulesTest.testDisplayNamesAreOneSentence);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesLongDisplayName"))
        .noneMatch(v -> v.contains("PassesDisplayName"));
  }

  @Test
  @DisplayName("JUnit Assertions를 잡고 AssertJ는 놓아둔다")
  void catchesJUnitAssertions() {
    // when
    List<String> found = violations(TestCodeRulesTest.assertJOnly);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesJUnitAssertions"))
        .noneMatch(v -> v.contains("PassesThreeAssertions"));
  }

  @Test
  @DisplayName("BDD then 진입점을 잡고 assertThat은 놓아둔다")
  void catchesBddAssertions() {
    // when
    List<String> found = violations(TestCodeRulesTest.noBddAssertions);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesBddAssertions"))
        .noneMatch(v -> v.contains("PassesThreeAssertions"));
  }

  @Test
  @DisplayName("표준 출력을 잡고 출력하지 않는 표본은 놓아둔다")
  void catchesStdOut() {
    // when
    List<String> found = violations(TestCodeRulesTest.noStdStreams);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesStdOut"))
        .noneMatch(v -> v.contains("PassesThreeAssertions"));
  }
}
