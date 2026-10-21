package io.scos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.scos.support.MeterRegistryAssert;
import java.lang.reflect.AccessibleObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * 테스트 코드 작성 규칙의 기계적 강제(TESTING §13). 제품 코드 규칙은 {@link ArchitectureRulesTest}. 허용 목록은 이 파일에만 둔다 — 늘릴
 * 때는 TESTING §13 표도 함께 고친다.
 */
@AnalyzeClasses(packages = "io.scos", importOptions = ImportOption.OnlyIncludeTests.class)
@DisplayName("테스트 코드는 기계적으로 강제하는 작성 규칙을 지킨다(TESTING §13)")
class TestCodeRulesTest {
  /** 조립 자체가 계약인 전체 기동 테스트(TESTING §1·§4.1). */
  static final Set<String> FULL_BOOT_ALLOWED =
      Set.of("io.scos.McpReadOnlyTest", "io.scos.CommandsSmokeTest", "io.scos.CommandScenario");

  /** 중첩·익명·람다 클래스의 바깥 최상위 클래스. 태그·이름 규칙은 최상위 클래스에 적용한다(JUnit @Tag는 중첩에 상속된다). */
  static JavaClass topLevel(JavaClass c) {
    JavaClass current = c;
    while (current.getEnclosingClass().isPresent()) current = current.getEnclosingClass().get();
    return current;
  }

  static boolean isTestMethod(JavaMethod m) {
    return m.isAnnotatedWith(Test.class) || m.isAnnotatedWith(ParameterizedTest.class);
  }

  static boolean declaresTests(JavaClass c) {
    return c.getMethods().stream().anyMatch(TestCodeRulesTest::isTestMethod);
  }

  static boolean hasTag(JavaClass c, String tag) {
    if (c.isAnnotatedWith(Tag.class)) return tag.equals(c.getAnnotationOfType(Tag.class).value());
    // @Tag를 여럿 달면(예: db + perf) 컴파일러가 @Tags 컨테이너로 묶는다
    return c.isAnnotatedWith(Tags.class)
        && Arrays.stream(c.getAnnotationOfType(Tags.class).value())
            .anyMatch(t -> tag.equals(t.value()));
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

  // ---- 1단계 ----

  @ArchTest
  static final ArchRule testMethodsHaveDisplayName =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .or()
          .areAnnotatedWith(ParameterizedTest.class)
          .should()
          .beAnnotatedWith(DisplayName.class)
          .because("사람이 읽는 문장은 @DisplayName이 맡는다(TESTING §2.2)");

  @ArchTest
  static final ArchRule assertJOnly =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveNameMatching("org\\.junit\\.jupiter\\.api\\.(Assertions|Assumptions)")
          .because("단언은 AssertJ만, 조용한 건너뛰기(assume) 금지(TESTING §8·§10)");

  @ArchTest
  static final ArchRule noBddAssertions =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveNameMatching("org\\.assertj\\.core\\.api\\.BDD\\w*")
          .because("단언 진입점은 assertThat…로 통일한다 — BDD then(…)은 단언 셈 규칙이 세지 않는다(TESTING §13)");

  @ArchTest
  static final ArchRule noMockito =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.mockito..")
          .because("포트는 메모리 참조 구현으로 대체한다(TESTING §4); 상호작용 계약이 필요하면 람다 fake + 카운터");

  @ArchTest
  static final ArchRule fullBootOnlyWhereAssemblyIsTheContract =
      noClasses()
          .that(DescribedPredicate.not(topLevelClassIn(FULL_BOOT_ALLOWED)))
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.boot.builder.SpringApplicationBuilder")
          .orShould()
          .beAnnotatedWith("org.springframework.boot.test.context.SpringBootTest")
          .because("전체 기동은 조립 자체가 계약일 때만(TESTING §4.1 허용 목록)");

  static final DescribedPredicate<JavaClass> USES_DATABASE =
      new DescribedPredicate<>("depend on Testcontainers or PostgresSupport") {
        @Override
        public boolean test(JavaClass c) {
          return c.getDirectDependenciesFromSelf().stream()
              .map(d -> d.getTargetClass())
              .anyMatch(
                  t ->
                      t.getPackageName().startsWith("org.testcontainers")
                          || t.getSimpleName().equals("PostgresSupport"));
        }
      };

  /** 데이터베이스를 여는 테스트 지원 클래스(테스트 아님) — 쓰는 테스트 쪽이 @Tag("db")를 단다. */
  static final Set<String> DATABASE_SUPPORT =
      Set.of(
          "io.scos.collection.internal.postgres.PostgresSupport",
          "io.scos.collection.internal.postgres.MigrationRehearsal");

  @ArchTest
  static final ArchRule databaseTestsAreTagged =
      classes()
          .that(USES_DATABASE)
          .and(DescribedPredicate.not(topLevelClassIn(DATABASE_SUPPORT)))
          .and()
          .haveSimpleNameNotEndingWith("Probe")
          .should(
              new ArchCondition<JavaClass>("belong to a top-level class annotated @Tag(\"db\")") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                  JavaClass top = topLevel(c);
                  if (!hasTag(top, "db"))
                    events.add(
                        SimpleConditionEvent.violated(
                            c,
                            c.getName()
                                + " uses the database but "
                                + top.getSimpleName()
                                + " has no @Tag(\"db\")"));
                }
              })
          .because("Docker가 필요한 테스트는 dbTest로만 돈다(ADR-0015)");

  /** JUnit이 부르는 메서드(테스트·생명주기)를 가진 클래스 — 도우미(ChildJvm·ScriptHarness·*Scenario)와 *Probe는 아니다. */
  static boolean hasJUnitMethods(JavaClass c) {
    return c.getMethods().stream()
        .anyMatch(
            m ->
                isTestMethod(m)
                    || m.isAnnotatedWith(BeforeEach.class)
                    || m.isAnnotatedWith(BeforeAll.class)
                    || m.isAnnotatedWith(AfterEach.class)
                    || m.isAnnotatedWith(AfterAll.class));
  }

  /** 프로세스를 띄우는 JDK 진입점: ProcessBuilder 전부와 Runtime.exec. */
  static boolean startsProcessDirectly(JavaCodeUnitAccess<?> access) {
    String owner = access.getTargetOwner().getName();
    return owner.equals("java.lang.ProcessBuilder")
        || (owner.equals("java.lang.Runtime") && access.getName().equals("exec"));
  }

  /**
   * 이 규칙의 import(테스트만)에는 제품 클래스가, 표본 import(scosrules)에는 io.scos 지원 클래스가 본문 없이 들어온다 — 따라 들어갈 때는
   * io.scos 전체(제품 + 테스트)를 import한 쪽의 같은 코드 단위를 쓴다.
   */
  static final JavaClasses WITH_BODIES = new ClassFileImporter().importPackages("io.scos");

  static JavaCodeUnit withBody(JavaCodeUnit unit) {
    String owner = unit.getOwner().getName();
    if (!WITH_BODIES.contain(owner)) return unit;
    return WITH_BODIES
        .get(owner)
        .tryGetCodeUnitWithParameterTypeNames(
            unit.getName(), unit.getRawParameterTypes().stream().map(JavaClass::getName).toList())
        .orElse(unit);
  }

  /**
   * 코드 단위가 프로세스를 띄우는가. 부르는(참조하는) io.scos 코드를 테스트·제품 가리지 않고 따라 들어간다 — ScriptHarness·ChildJvm.start…·
   * *Scenario·CommandCredentials를 거쳐도 잡히고, ChildJvm.touch·awaitCondition처럼 띄우지 않는 도우미는 걸리지 않는다.
   * 인터페이스 디스패치는 따라가지 못한다.
   */
  static boolean startsProcess(JavaCodeUnit unit, Set<String> visited) {
    if (!visited.add(unit.getFullName())) return false;
    List<JavaCodeUnitAccess<?>> accesses = new ArrayList<>(unit.getCallsFromSelf());
    accesses.addAll(unit.getCodeUnitReferencesFromSelf());
    for (JavaCodeUnitAccess<?> access : accesses) {
      if (startsProcessDirectly(access)) return true;
      JavaCodeUnit target = access.getTarget().resolveMember().orElse(null);
      if (target == null) continue;
      String pkg = target.getOwner().getPackageName();
      if ((pkg.startsWith("io.scos") || pkg.startsWith("scosrules"))
          && startsProcess(withBody(target), visited)) return true;
    }
    return false;
  }

  static final DescribedPredicate<JavaClass> STARTS_PROCESSES =
      new DescribedPredicate<>("have JUnit methods that start processes (ProcessBuilder, exec)") {
        @Override
        public boolean test(JavaClass c) {
          if (!hasJUnitMethods(c)) return false;
          Set<String> visited = new HashSet<>();
          return c.getCodeUnits().stream().anyMatch(u -> startsProcess(u, visited));
        }
      };

  @ArchTest
  static final ArchRule processTestsAreTagged =
      classes()
          .that(STARTS_PROCESSES)
          .should(
              new ArchCondition<JavaClass>(
                  "belong to a top-level class annotated @Tag(\"process\")") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                  JavaClass top = topLevel(c);
                  if (!hasTag(top, "process"))
                    events.add(
                        SimpleConditionEvent.violated(
                            c,
                            c.getName()
                                + " starts processes but "
                                + top.getSimpleName()
                                + " has no @Tag(\"process\")"));
                }
              })
          .because("bash·자식 JVM을 띄우는 테스트는 fastTest 밖, process 층에서 돈다(TESTING §6.4)");

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
                            c,
                            c.getName()
                                + (isAbstract
                                    ? " is an abstract contract but not named *Contract"
                                    : " contains tests but is not named *Test/*Tests")));
                }
              })
          .because("이름 규칙(TESTING §2.1)");

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

  // ---- 2단계 ----

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
          .should()
          .callMethodWhere(SLEEP_CALL)
          .because("순서는 gate·서버 측 조건 대기로 만든다(TESTING §7) — 폴링은 Awaitility(ChildJvm)");

  /** 조건 폴링의 유일한 구현(ChildJvm.awaitUntil). 테스트·시나리오는 Eventually.await(자식 JVM 프로브는 ChildJvm)를 거친다. */
  static final Set<String> AWAITILITY_ALLOWED = Set.of("io.scos.support.ChildJvm");

  @ArchTest
  static final ArchRule awaitilityOnlyInChildJvm =
      noClasses()
          .that(DescribedPredicate.not(topLevelClassIn(AWAITILITY_ALLOWED)))
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.awaitility..")
          .because("Awaitility는 조건 폴링의 유일한 구현 ChildJvm.awaitUntil에서만 쓴다(TESTING §7)");

  @ArchTest
  static final ArchRule noStdStreamsExceptProbes =
      noClasses()
          .that()
          .haveSimpleNameNotEndingWith("Probe")
          .should()
          .accessField(System.class, "out")
          .orShould()
          .accessField(System.class, "err")
          .because("테스트는 표준 출력에 결과를 찍지 않는다; 자식 프로세스 *Probe의 RESULT 줄만 예외(TESTING §10)");

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
          .because("테스트가 제품 private 멤버에 reflection으로 접근하지 않는다(TESTING §13)");

  /** 격리는 "격리: <조건> — #<이슈>, 만료 P<n>", 미구현 명세는 "spec: …"(TESTING §10). */
  static final Pattern DISABLED_FORMAT =
      Pattern.compile("^(격리: .+ — #\\d+, 만료 P\\d)$|^(spec: .+)$");

  static <T extends HasAnnotations<?> & HasDescription>
      ArchCondition<T> haveWellFormedDisabledReason() {
    return new ArchCondition<>("have a @Disabled reason matching " + DISABLED_FORMAT) {
      @Override
      public void check(T item, ConditionEvents events) {
        String reason = item.getAnnotationOfType(Disabled.class).value();
        if (!DISABLED_FORMAT.matcher(reason).matches())
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getDescription()
                      + " has @Disabled(\""
                      + reason
                      + "\") — 이유·이슈·만료 단계 형식이 아니다"));
      }
    };
  }

  @ArchTest
  static final ArchRule disabledMethodsHaveReason =
      methods()
          .that()
          .areAnnotatedWith(Disabled.class)
          .should(haveWellFormedDisabledReason())
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule disabledClassesHaveReason =
      classes()
          .that()
          .areAnnotatedWith(Disabled.class)
          .should(haveWellFormedDisabledReason())
          .allowEmptyShould(true);

  // ---- 3단계: 읽히는 테스트의 모양(TESTING §2.5) ----

  static final DescribedPredicate<JavaMethod> TEST_METHODS =
      new DescribedPredicate<>("test methods") {
        @Override
        public boolean test(JavaMethod m) {
          return isTestMethod(m);
        }
      };

  static ArchCondition<JavaMethod> notCall(String what, DescribedPredicate<JavaCall<?>> target) {
    return new ArchCondition<>("not " + what) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        method.getCallsFromSelf().stream()
            .filter(target)
            .forEach(
                call ->
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " " + what + ": " + call.getDescription())));
      }
    };
  }

  static DescribedPredicate<JavaCall<?>> ownedBy(Class<?> owner) {
    return JavaCall.Predicates.target(
        HasOwner.Predicates.With.owner(JavaClass.Predicates.equivalentTo(owner)));
  }

  static final DescribedPredicate<JavaCall<?>> SYNCHRONIZATION_SETUP =
      JavaCall.Predicates.target(HasName.Predicates.name("<init>"))
          .and(
              JavaCall.Predicates.target(
                  HasOwner.Predicates.With.owner(
                      JavaClass.Predicates.assignableTo(CountDownLatch.class)
                          .or(JavaClass.Predicates.assignableTo(CyclicBarrier.class))
                          .or(JavaClass.Predicates.assignableTo(Thread.class)))))
          .or(ownedBy(Executors.class));

  @ArchTest
  static final ArchRule testMethodsLeaveSynchronizationToContexts =
      methods()
          .that(TEST_METHODS)
          .should(notCall("builds latches/threads/executors", SYNCHRONIZATION_SETUP))
          .because("동기화는 테스트 컨텍스트가 소유한다(TESTING §2.5 규칙 1·§7)");

  /** ofNanos는 경과 측정(System.nanoTime 차이)에 쓰여 리터럴이 아니다 — 제외. */
  static final DescribedPredicate<JavaCall<?>> DURATION_LITERAL =
      ownedBy(Duration.class)
          .and(
              JavaCall.Predicates.target(
                  HasName.Predicates.nameMatching("of(Days|Hours|Minutes|Seconds|Millis)?|parse")));

  @ArchTest
  static final ArchRule testMethodsUseNamedDurations =
      methods()
          .that(TEST_METHODS)
          .should(notCall("builds a Duration literal", DURATION_LITERAL))
          .because("값은 역할 이름 상수로 쓴다(TESTING §2.5 규칙 4)");

  static final DescribedPredicate<JavaCall<?>> METER_LOOKUP =
      JavaCall.Predicates.target(HasName.Predicates.nameMatching("find|get"))
          .and(
              JavaCall.Predicates.target(
                  HasOwner.Predicates.With.owner(
                      JavaClass.Predicates.assignableTo(
                          "io.micrometer.core.instrument.MeterRegistry"))));

  @ArchTest
  static final ArchRule testMethodsAssertMetricsThroughMeterRegistryAssert =
      methods()
          .that(TEST_METHODS)
          .should(notCall("looks up meters directly", METER_LOOKUP))
          .because("지표 단언은 MeterRegistryAssert로 한다(TESTING §8)");

  /** 조건 대기의 유일한 진입점(Eventually)과 그 구현·자식 JVM 쪽 지원. */
  static final Set<String> CONDITION_WAIT_ALLOWED =
      Set.of("io.scos.support.Eventually", "io.scos.support.ChildJvm", "io.scos.support.PgLocks");

  @ArchTest
  static final ArchRule conditionWaitsGoThroughEventually =
      noClasses()
          .that(DescribedPredicate.not(topLevelClassIn(CONDITION_WAIT_ALLOWED)))
          .and()
          .haveSimpleNameNotEndingWith("Probe")
          .should()
          .callMethodWhere(
              JavaCall.Predicates.target(HasName.Predicates.name("awaitCondition"))
                  .and(
                      JavaCall.Predicates.target(
                          HasOwner.Predicates.With.owner(
                              HasName.Predicates.name("io.scos.support.ChildJvm")))))
          .because("조건 대기는 Eventually.await 한 곳을 거친다(TESTING §7)");

  /** 테스트 하나 = 계약 하나: 본문의 단언 진입점(assertThat…)은 이 수를 넘지 않는다. 전제 단언도 센다. */
  static final int MAX_ASSERTIONS_PER_TEST = 3;

  /** AssertJ·MeterRegistryAssert의 단언 진입점. 람다 본문의 호출은 ArchUnit이 바깥 메서드에 귀속하므로 함께 센다. */
  static final DescribedPredicate<JavaCall<?>> ASSERTION_ENTRY =
      JavaCall.Predicates.target(HasName.Predicates.nameStartingWith("assertThat"))
          .and(ownedBy(Assertions.class).or(ownedBy(MeterRegistryAssert.class)));

  /** 단언 클래스(AbstractAssert 하위)의 메서드는 단언 진입점 뒤의 연쇄라 따라 들어가지 않는다. */
  static boolean isAssertionClass(JavaClass c) {
    return c.isAssignableTo(AbstractAssert.class);
  }

  /**
   * 테스트 메서드가 닿는 단언 진입점 수. 부르는 메서드·생성자(테스트 소스에 한정하지 않고 ArchUnit이 해석하는 것 전부)를 따라 들어가 그 안의 단언도 센다 —
   * 도우미로 옮겨 상한을 피할 수 없게. 이름이 assert…인 메서드는 단언 하나로 센다(TESTING §8). 람다 본문의 호출은 ArchUnit이 바깥 메서드에
   * 귀속하므로 이미 포함된다. 세지 못하는 우회(인터페이스 디스패치·필드 람다·메서드 참조)는 TESTING §13.
   */
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
          || isAssertionClass(helper.getOwner())
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

  /** 제목은 한 문장: 길면 계약을 둘 이상 담고 있다는 신호다. 세부(ADR 번호·예시 값)는 주석으로. */
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
