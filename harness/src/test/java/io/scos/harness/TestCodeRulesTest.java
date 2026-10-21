package io.scos.harness;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.AccessibleObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/** 하네스 테스트 코드 규칙(TESTING §13의 부분집합). backend의 TestCodeRulesTest와 같은 규칙, 허용 목록만 다르다. */
@AnalyzeClasses(packages = "io.scos.harness", importOptions = ImportOption.OnlyIncludeTests.class)
class TestCodeRulesTest {
  /** 실행 기록 자체가 산출물인 하네스 보고 클래스와 자식 프로세스 대역(TESTING §10·§13). */
  static final Set<String> STDOUT_ALLOWED =
      Set.of("io.scos.harness.java.JavaBaselineTest", "io.scos.harness.testing.FakeMcpServer");

  /** hang·EOF 무시를 모사하는 자식 프로세스 대역. */
  static final Set<String> SLEEP_ALLOWED = Set.of("io.scos.harness.testing.FakeMcpServer");

  static JavaClass topLevel(JavaClass c) {
    JavaClass current = c;
    while (current.getEnclosingClass().isPresent()) current = current.getEnclosingClass().get();
    return current;
  }

  /** 허용 목록은 FQCN이다 — 다른 패키지에 같은 단순 이름의 클래스를 만들어 규칙을 우회하지 못하게. */
  static DescribedPredicate<JavaClass> topLevelClassIn(Set<String> fullyQualifiedNames) {
    return new DescribedPredicate<>("top-level class in " + fullyQualifiedNames) {
      @Override
      public boolean test(JavaClass c) {
        return fullyQualifiedNames.contains(topLevel(c).getName());
      }
    };
  }

  static boolean declaresTests(JavaClass c) {
    return c.getMethods().stream()
        .anyMatch(m -> m.isAnnotatedWith(Test.class) || m.isAnnotatedWith(ParameterizedTest.class));
  }

  @ArchTest
  static final ArchRule testMethodsHaveDisplayName =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .or()
          .areAnnotatedWith(ParameterizedTest.class)
          .should()
          .beAnnotatedWith(DisplayName.class);

  @ArchTest
  static final ArchRule assertJOnly =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveNameMatching("org\\.junit\\.jupiter\\.api\\.(Assertions|Assumptions)");

  @ArchTest
  static final ArchRule noBddAssertions =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveNameMatching("org\\.assertj\\.core\\.api\\.BDD\\w*")
          .because("단언 진입점은 assertThat…로 통일한다 — BDD then(…)은 단언 셈 규칙이 세지 않는다(TESTING §13)");

  @ArchTest
  static final ArchRule testClassNames =
      classes()
          .that(
              new DescribedPredicate<>(
                  "classes that declare tests (checked on their top-level class)") {
                @Override
                public boolean test(JavaClass c) {
                  return !c.isAnonymousClass() && declaresTests(c);
                }
              })
          .should(
              new ArchCondition<JavaClass>("be named *Test/*Tests, or *Contract when abstract") {
                @Override
                public void check(JavaClass declaring, ConditionEvents events) {
                  JavaClass c = topLevel(declaring); // @Nested만 가진 최상위 클래스도 중첩 클래스를 통해 검사된다
                  boolean isAbstract = c.getModifiers().contains(JavaModifier.ABSTRACT);
                  String name = c.getSimpleName();
                  boolean ok =
                      isAbstract
                          ? name.endsWith("Contract")
                          : name.endsWith("Test") || name.endsWith("Tests");
                  if (!ok)
                    events.add(
                        SimpleConditionEvent.violated(
                            c, c.getName() + " breaks the test class naming rule"));
                }
              });

  @ArchTest
  static final ArchRule testClassesHaveDisplayName =
      classes()
          .that(
              new DescribedPredicate<>(
                  "classes that declare or inherit tests (checked on their top-level class)") {
                @Override
                public boolean test(JavaClass c) {
                  return !c.isAnonymousClass()
                      && c.getAllMethods().stream().anyMatch(TestCodeRulesTest::isTestMethod);
                }
              })
          .should(
              new ArchCondition<JavaClass>("have a @DisplayName on their top-level class") {
                @Override
                public void check(JavaClass declaring, ConditionEvents events) {
                  JavaClass c = topLevel(declaring);
                  if (!c.isAnnotatedWith(DisplayName.class))
                    events.add(
                        SimpleConditionEvent.violated(
                            c, c.getName() + " has tests but no class @DisplayName"));
                }
              })
          .because("클래스 제목이 규칙과 근거를 말한다 — @DisplayName은 상속되지 않는다(TESTING §0 C5)");

  /** Thread.sleep(long)·sleep(long,int)·sleep(Duration)과 TimeUnit.sleep 전부. */
  static final DescribedPredicate<JavaCall<?>> SLEEP_CALL =
      JavaCall.Predicates.target(HasName.Predicates.name("sleep"))
          .and(
              JavaCall.Predicates.target(
                  HasOwner.Predicates.With.owner(
                      JavaClass.Predicates.assignableTo(Thread.class)
                          .or(JavaClass.Predicates.assignableTo(TimeUnit.class)))));

  @ArchTest
  static final ArchRule noThreadSleep =
      noClasses()
          .that(DescribedPredicate.not(topLevelClassIn(SLEEP_ALLOWED)))
          .should()
          .callMethodWhere(SLEEP_CALL)
          .because("순서는 gate·조건 대기로 만든다(TESTING §7)");

  @ArchTest
  static final ArchRule noStdStreams =
      noClasses()
          .that(DescribedPredicate.not(topLevelClassIn(STDOUT_ALLOWED)))
          .should()
          .accessField(System.class, "out")
          .orShould()
          .accessField(System.class, "err")
          .because("테스트는 표준 출력에 결과를 찍지 않는다(TESTING §10)");

  @ArchTest
  static final ArchRule noSetAccessible =
      noClasses()
          .should()
          .callMethodWhere(
              JavaCall.Predicates.target(HasName.Predicates.name("setAccessible"))
                  .and(
                      JavaCall.Predicates.target(
                          HasOwner.Predicates.With.owner(
                              JavaClass.Predicates.assignableTo(AccessibleObject.class)))))
          .because("테스트의 reflection은 금지(TESTING §13)");

  static boolean isTestMethod(JavaMethod m) {
    return m.isAnnotatedWith(Test.class) || m.isAnnotatedWith(ParameterizedTest.class);
  }

  static final DescribedPredicate<JavaMethod> TEST_METHODS =
      new DescribedPredicate<>("test methods") {
        @Override
        public boolean test(JavaMethod m) {
          return isTestMethod(m);
        }
      };

  /**
   * backend TestCodeRulesTest와 같은 상한: 테스트가 닿는 단언 진입점(assertThat…) 수. 부르는 메서드·생성자 안의 단언도 세고, 람다 본문의
   * 호출은 ArchUnit이 바깥 메서드에 귀속하므로 함께 센다(TESTING §13).
   */
  static final int MAX_ASSERTIONS_PER_TEST = 3;

  static final DescribedPredicate<JavaCall<?>> ASSERTION_ENTRY =
      JavaCall.Predicates.target(HasName.Predicates.nameStartingWith("assertThat"))
          .and(
              JavaCall.Predicates.target(
                  HasOwner.Predicates.With.owner(
                      JavaClass.Predicates.equivalentTo(Assertions.class))));

  /** backend TestCodeRulesTest.assertionsReachedFrom과 같은 셈: 도우미를 따라 들어가고, assert… 도우미는 하나로 센다. */
  static long assertionsReachedFrom(JavaCodeUnit method, Set<JavaCodeUnit> path) {
    long count = 0;
    for (JavaCall<?> call : method.getCallsFromSelf()) {
      if (ASSERTION_ENTRY.test(call)) {
        count++;
        continue;
      }
      // 메서드와 생성자 둘 다 따라간다(생성자 안 단언으로 상한을 피하지 못하게)
      JavaCodeUnit helper = call.getTarget().resolveMember().orElse(null);
      if (helper == null) continue;
      if ((helper instanceof JavaMethod m && isTestMethod(m))
          || helper.getOwner().isAssignableTo(AbstractAssert.class)
          || path.contains(helper)) continue;
      if (helper.getName().startsWith("assert")) {
        count++;
        continue;
      }
      path.add(helper);
      count += assertionsReachedFrom(helper, path);
      path.remove(helper);
    }
    return count;
  }

  @ArchTest
  static final ArchRule testMethodsCheckOneContract =
      methods()
          .that(TEST_METHODS)
          .should(
              new ArchCondition<>(
                  "reach at most " + MAX_ASSERTIONS_PER_TEST + " assertion entries") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  long assertions = assertionsReachedFrom(method, new HashSet<>());
                  if (assertions > MAX_ASSERTIONS_PER_TEST)
                    events.add(
                        SimpleConditionEvent.violated(
                            method, method.getFullName() + " has " + assertions + " assertions"));
                }
              })
          .because("단언을 줄이지 말고 계약별 테스트로 나눈다(TESTING §2.5 규칙 2)");

  static final int MAX_DISPLAY_NAME_LENGTH = 60;

  static <T extends HasAnnotations<?> & HasDescription> ArchCondition<T> haveShortDisplayName() {
    return new ArchCondition<>(
        "have a @DisplayName of at most " + MAX_DISPLAY_NAME_LENGTH + " chars") {
      @Override
      public void check(T item, ConditionEvents events) {
        if (!item.isAnnotatedWith(DisplayName.class)) return;
        String name = item.getAnnotationOfType(DisplayName.class).value();
        if (name.codePointCount(0, name.length()) > MAX_DISPLAY_NAME_LENGTH)
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getDescription() + " has a long @DisplayName: " + name));
      }
    };
  }

  @ArchTest
  static final ArchRule testDisplayNamesAreOneSentence =
      methods()
          .that(TEST_METHODS)
          .should(haveShortDisplayName())
          .because("제목은 한 문장이다(TESTING §2.5 규칙 2)");

  @ArchTest
  static final ArchRule nestedDisplayNamesAreOneSentence =
      classes()
          .that()
          .areAnnotatedWith(DisplayName.class)
          .should(haveShortDisplayName())
          .because("상황 제목도 한 문장이다(TESTING §2.5 규칙 2)");
}
