package io.scos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;

/**
 * 코딩 규약 규칙(TESTING §13.2): public 자유 함수 금지(팩토리·순수 계산 모듈 제외)와 유틸 이름 클래스 금지. 대상은 제품 main 클래스(harness
 * 소스셋· scos-test-support·testFixtures 제외). 기존 위반은 {@code src/test/resources/frozen}에 고정했다 — 새 위반은
 * 실패하고, 고친 위반은 저장소 갱신을 요구한다(archunit.properties: refreeze·저장소 생성·갱신 모두 꺼짐).
 */
@AnalyzeClasses(packages = "io.scos", importOptions = TestLocations.ProductMainCode.class)
@DisplayName("제품 코드는 정적 함수·유틸 클래스 규약을 지킨다(TESTING §13.2)")
class ConventionRulesTest {
  /** 고정하기 전의 규칙(자체 테스트가 표본에 돌린다). 설명 문장은 고정 저장소의 열쇠다 — 바꾸면 저장소를 다시 만들어야 한다. */
  static final ArchRule PUBLIC_STATIC_METHODS_ARE_FACTORIES =
      methods()
          .that()
          .arePublic()
          .and()
          .areStatic()
          .should(beFactoriesOrMain())
          .as(
              "제품 public static 메서드는 자기 타입 팩토리, 계약을 돌려주는 조립 팩토리, 순수 계산 모듈(@PureFunctions)의 함수, main 중 하나다");

  /** 순수 계산 모듈 선언 애노테이션(이름으로 본다 — 클래스 파일 보존이라 ArchUnit이 읽는다). */
  static final String PURE_FUNCTIONS = "io.scos.engine.support.PureFunctions";

  static final ArchRule NO_UTILITY_NAMES =
      noClasses()
          .should()
          .haveSimpleNameEndingWith("Utils")
          .orShould()
          .haveSimpleNameEndingWith("Util")
          .orShould()
          .haveSimpleNameEndingWith("Helper")
          .as("제품 클래스 이름은 *Utils·*Util·*Helper로 끝나지 않는다");

  @ArchTest
  static final ArchRule publicStaticMethodsAreFactories =
      FreezingArchRule.freeze(PUBLIC_STATIC_METHODS_ARE_FACTORIES);

  @ArchTest static final ArchRule noUtilityNames = FreezingArchRule.freeze(NO_UTILITY_NAMES);

  /** 순수 계산 모듈 선언의 구조 검증 — 고정하지 않는다(위반 0에서 시작). */
  @ArchTest
  static final ArchRule pureFunctionModulesAreStateless =
      classes()
          .that()
          .areAnnotatedWith(PURE_FUNCTIONS)
          .should(beStatelessFunctionModules())
          .as(
              "@PureFunctions 클래스는 final이고 private 생성자만 있고 상태(인스턴스 필드·final 아닌 static 필드)가 없고 public"
                  + " static 함수가 계약(java 밖 인터페이스)을 인자로 받지 않는다");

  /**
   * 허용되는 public static 메서드(CODING-GUIDELINES §1.1·§2.5, TESTING §13.2):
   *
   * <ol>
   *   <li>자기 타입 팩토리 — 돌려주는 타입(지움)이 선언한 타입(하위 타입 포함 — sealed 계층의 {@code Task.file(…)})이거나 {@code
   *       Optional<선언한 타입>}이다(of·parse·from 등 이름은 보지 않는다).
   *   <li>조립 팩토리(§2.5 {@code createEmbedder(config): Embedder}) — 선언 클래스가 인스턴스를 만들 수 없는 팩토리
   *       클래스(enum·record· 인터페이스가 아니고 생성자가 모두 private, 인스턴스 필드 없음)이고 돌려주는 타입이 계약(java 밖 인터페이스)이다.
   *   <li>순수 계산 모듈(§1.1 예외 2) — 선언 클래스에 {@code @PureFunctions}가 있다. 선언이 맞는지는 {@link
   *       #pureFunctionModulesAreStateless}가 본다.
   * </ol>
   *
   * 진입점 {@code main(String[])}과 enum의 암묵 {@code values()}·합성 메서드는 규칙 밖이다.
   */
  static boolean isFactoryOrEntryPoint(JavaMethod method) {
    JavaClass owner = method.getOwner();
    if (method.getModifiers().contains(JavaModifier.SYNTHETIC)) return true;
    if (owner.isEnum() && method.getName().equals("values") && method.getParameters().isEmpty())
      return true;
    if (isMain(method)) return true;
    if (method.getRawReturnType().isAssignableTo(owner.getName())) return true;
    if (isOptionalOf(method.getReturnType(), owner)) return true;
    if (isFactoryClass(owner) && isContract(method.getRawReturnType())) return true;
    return owner.isAnnotatedWith(PURE_FUNCTIONS);
  }

  /** 인스턴스를 만들 수 없고 상태가 없는 클래스: enum·record·인터페이스가 아니고 생성자가 모두 private, 인스턴스 필드 없음. */
  static boolean isFactoryClass(JavaClass owner) {
    if (owner.isInterface() || owner.isEnum() || owner.isRecord()) return false;
    boolean onlyPrivateConstructors =
        !owner.getConstructors().isEmpty()
            && owner.getConstructors().stream()
                .allMatch(c -> c.getModifiers().contains(JavaModifier.PRIVATE));
    boolean noInstanceFields =
        owner.getFields().stream().allMatch(f -> f.getModifiers().contains(JavaModifier.STATIC));
    return onlyPrivateConstructors && noInstanceFields;
  }

  /**
   * 계약: java 밖의 봉인되지 않은 인터페이스(SPI·모듈의 포트). {@code List}·{@code Runnable} 같은 JDK 인터페이스와 값의 합 타입인
   * 봉인(sealed) 인터페이스는 계약이 아니다.
   */
  static boolean isContract(JavaClass type) {
    return type.isInterface()
        && !type.getPackageName().startsWith("java.")
        && !type.reflect().isSealed();
  }

  private static ArchCondition<JavaClass> beStatelessFunctionModules() {
    return new ArchCondition<>("be stateless function modules") {
      @Override
      public void check(JavaClass module, ConditionEvents events) {
        List<String> problems = new ArrayList<>();
        if (!module.getModifiers().contains(JavaModifier.FINAL)) problems.add("not final");
        if (!isFactoryClass(module)) problems.add("instantiable or has instance fields");
        module.getFields().stream()
            .filter(f -> !f.getModifiers().contains(JavaModifier.FINAL))
            .forEach(f -> problems.add("non-final field " + f.getName()));
        module.getMethods().stream()
            .filter(
                m ->
                    m.getModifiers().containsAll(List.of(JavaModifier.PUBLIC, JavaModifier.STATIC)))
            .flatMap(
                m ->
                    m.getRawParameterTypes().stream()
                        .filter(ConventionRulesTest::isContract)
                        .map(t -> m.getName() + " takes " + t.getName()))
            .forEach(problems::add);
        if (!problems.isEmpty())
          events.add(
              SimpleConditionEvent.violated(
                  module,
                  module.getName() + " " + problems + " in " + module.getSourceCodeLocation()));
      }
    };
  }

  private static boolean isMain(JavaMethod method) {
    return method.getName().equals("main")
        && method.getRawReturnType().getName().equals("void")
        && method.getRawParameterTypes().size() == 1
        && method.getRawParameterTypes().get(0).getName().equals(String[].class.getName());
  }

  private static boolean isOptionalOf(JavaType type, JavaClass owner) {
    if (!(type instanceof JavaParameterizedType parameterized)
        || !type.toErasure().isEquivalentTo(Optional.class)) return false;
    List<JavaType> arguments = parameterized.getActualTypeArguments();
    return arguments.size() == 1 && arguments.get(0).toErasure().isAssignableTo(owner.getName());
  }

  private static ArchCondition<JavaMethod> beFactoriesOrMain() {
    return new ArchCondition<>("be factories or main") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (!isFactoryOrEntryPoint(method))
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " returns "
                      + method.getRawReturnType().getName()
                      + " in "
                      + method.getSourceCodeLocation()));
      }
    };
  }
}
