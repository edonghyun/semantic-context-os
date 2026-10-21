package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 테스트 코드 규칙 자체의 계약(F15 T15.2, tr-search-misc High 8·tr-vocab-harness High 9): 규칙의 술어가 아무것도 고르지 않게
 * 되면 모든 테스트가 녹색이다 — 위반 표본(scosrules.Violates…)은 잡고 통과 표본(Passes…)은 잡지 않는지 본다. harness에도 같은 표본·같은
 * 테스트가 있어 두 단언 셈 사본이 갈라지면 한쪽이 실패한다.
 */
@DisplayName("테스트 코드 규칙은 위반 표본을 잡고 통과 표본은 놓아둔다(TESTING §13)")
class TestCodeRulesSelfTest {
  static final JavaClasses SAMPLES = new ClassFileImporter().importPackages("scosrules");

  /** 규칙을 표본에 돌린 위반 설명들. */
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
  @DisplayName("테스트 본문의 Duration 리터럴을 잡고 이름 있는 상수는 놓아둔다")
  void catchesDurationLiteral() {
    // when
    List<String> found = violations(TestCodeRulesTest.testMethodsUseNamedDurations);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesDurationLiteral"))
        .noneMatch(v -> v.contains("PassesNamedDuration"));
  }

  @Test
  @DisplayName("@Disabled 이유가 격리·spec 형식이 아니면 잡는다")
  void catchesMalformedDisabled() {
    // when
    List<String> found = violations(TestCodeRulesTest.disabledMethodsHaveReason);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesDisabledReason"))
        .noneMatch(v -> v.contains("PassesDisabled"));
  }

  @Test
  @DisplayName("데이터베이스를 쓰는데 @Tag(db)가 없는 클래스를 잡는다")
  void catchesUntaggedDatabase() {
    // when
    List<String> found = violations(TestCodeRulesTest.databaseTestsAreTagged);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesUntaggedDatabase"))
        .noneMatch(v -> v.contains("PassesTaggedDatabase"));
  }

  @Test
  @DisplayName("프로세스를 띄우는데 @Tag(process)가 없는 클래스를 잡는다")
  void catchesUntaggedProcess() {
    // when
    List<String> found = violations(TestCodeRulesTest.processTestsAreTagged);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesUntaggedProcess "))
        .noneMatch(v -> v.contains("PassesTaggedProcess"));
  }

  @Test
  @DisplayName("도우미를 거쳐 띄워도 잡고, 띄우지 않는 도우미는 놓아둔다")
  void followsHelpers() {
    // when
    List<String> found = violations(TestCodeRulesTest.processTestsAreTagged);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesUntaggedProcessThroughHelper "))
        .noneMatch(v -> v.contains("PassesChildJvmWithoutProcess"));
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
  @DisplayName("표준 출력을 잡고 *Probe의 결과 줄은 놓아둔다")
  void catchesStdOut() {
    // when
    List<String> found = violations(TestCodeRulesTest.noStdStreamsExceptProbes);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesStdOut"))
        .noneMatch(v -> v.contains("PassesResultLineProbe"));
  }

  @Test
  @DisplayName("테스트 본문의 래치 생성을 잡고 컨텍스트 안의 것은 놓아둔다")
  void catchesSynchronizationInTest() {
    // when
    List<String> found = violations(TestCodeRulesTest.testMethodsLeaveSynchronizationToContexts);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesLatchInTest"))
        .noneMatch(v -> v.contains("PassesLatchInContext"));
  }

  @Test
  @DisplayName("테스트 본문의 지표 탐색을 잡고 MeterRegistryAssert는 놓아둔다")
  void catchesMeterLookup() {
    // when
    List<String> found =
        violations(TestCodeRulesTest.testMethodsAssertMetricsThroughMeterRegistryAssert);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesMeterLookup"))
        .noneMatch(v -> v.contains("PassesMeterRegistryAssert"));
  }

  @Test
  @DisplayName("ChildJvm 밖의 Awaitility 의존을 잡고 Eventually는 놓아둔다")
  void catchesAwaitility() {
    // when
    List<String> found = violations(TestCodeRulesTest.awaitilityOnlyInChildJvm);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesAwaitility"))
        .noneMatch(v -> v.contains("PassesEventually"));
  }

  @Test
  @DisplayName("ChildJvm.awaitCondition 직접 호출을 잡고 Eventually는 놓아둔다")
  void catchesDirectAwaitCondition() {
    // when
    List<String> found = violations(TestCodeRulesTest.conditionWaitsGoThroughEventually);

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesDirectAwaitCondition"))
        .noneMatch(v -> v.contains("PassesEventually"));
  }
}
