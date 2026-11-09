package io.scos;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static io.scos.BoundaryConditions.dependOnInternalOfAnotherModule;
import static io.scos.BoundaryConditions.notReferenceTypesInSignatureFrom;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;

/**
 * 공개 경계 규칙(단계 2 §4.2, 단계 3 T8): spi는 JDK만, Boot는 조립자만, internal은 자기 모듈 안, 공개 시그니처에 internal·support
 * 없음. 모듈 사이 방향(엔진은 수집을 모른다, 커넥터끼리 독립, boot는 server를 모른다)은 Gradle 모듈 의존 선언과 checkModuleGraph가
 * 강제한다(단계 4 T6).
 */
@AnalyzeClasses(packages = "io.scos", importOptions = TestLocations.ProductCode.class)
@DisplayName("공개 경계는 spi·engine·internal 규칙을 지킨다(단계 2 §4.2)")
class PublicBoundaryRulesTest {

  /**
   * R1: SPI는 JDK와 SPI만 안다 — 외부 구현자가 Spring·Jackson 버전에 묶이지 않는다. 예외는 null 표기 JSpecify 애노테이션
   * 하나다(compileOnly — 실행 클래스패스에 오면 checkModuleGraph가 실패한다, TESTING §13.2). ArchUnit은 TYPE_USE
   * 애노테이션을 의존으로 가져오지 않아 지금은 허용 없이도 통과하지만, 그 우연에 기대지 않고 의도를 적는다.
   */
  @ArchTest
  static final ArchRule spiDependsOnJdkOnly =
      classes()
          .that()
          .resideInAPackage("io.scos.spi..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("io.scos.spi..", "java..", "org.jspecify.annotations..");

  /**
   * R2: Spring Boot는 starter(boot)와 server에서만 쓴다 — Boot 없는 Spring 앱도 engine을 쓸 수 있다. 하네스는 테스트
   * 클래스패스에만 있고(ProductCode가 harness 소스셋을 제품으로 센다) 제품 jar에는 없으므로 예외다.
   */
  @ArchTest
  static final ArchRule bootOnlyInStarterAndServer =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.scos.boot..", "io.scos.server..", "io.scos.harness..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.boot..");

  /** R3: internal은 자기 모듈과 조립자(boot·server) 밖으로 나가지 않는다 — 모듈 내부를 바꿔도 이웃이 깨지지 않는다. */
  @ArchTest
  static final ArchRule internalStaysInItsModule =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.scos.boot..", "io.scos.server..")
          .should(dependOnInternalOfAnotherModule());

  /**
   * R4: 클라이언트는 공개 패키지만 쓴다 — 자기 패키지, spi, 그리고 engine·mcp·store.sqlite의 루트 패키지(정확히 그 패키지)뿐이다.
   * engine.support·mcp.wire·collection·internal은 호환 보장 밖이라 쓰지 않는다.
   */
  @ArchTest
  static final ArchRule clientUsesPublicPackagesOnly = onlyPublicPackages("io.scos.client..");

  /** R4의 규칙 본체. 대상 패키지를 받아 표본(ClientBoundarySelfTest)에도 같은 규칙을 돌린다. */
  static ArchRule onlyPublicPackages(String clientPackage) {
    return noClasses()
        .that()
        .resideInAPackage(clientPackage)
        .should()
        .dependOnClassesThat(
            resideInAPackage("io.scos..")
                .and(
                    not(
                        resideInAnyPackage(
                            clientPackage,
                            "io.scos.spi..",
                            "io.scos.engine",
                            "io.scos.mcp",
                            "io.scos.store.sqlite"))));
  }

  /** R3′: 공개 API(engine·spi)의 public 시그니처에 internal·support 타입이 나오지 않는다 — 호환 보장 밖 타입이 새지 않는다. */
  @ArchTest
  static final ArchRule publicApiExposesNoInternal =
      codeUnits()
          .that()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .resideInAnyPackage("io.scos.engine..", "io.scos.spi..")
          .and()
          .areDeclaredInClassesThat()
          .resideOutsideOfPackages("io.scos..internal..", "io.scos..support..")
          .should(notReferenceTypesInSignatureFrom("io.scos..internal..", "io.scos..support.."));
}
