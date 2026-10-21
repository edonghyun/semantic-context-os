package io.scos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 경계 규칙(PublicBoundaryRulesTest) 밖의 제품 의존 규칙(ADR-0001·0012): SPI와 공용 텍스트 도구(옛 shared)는 Spring 의존
 * 0, 엔진·도구는 JDBC·pgvector를 모른다(포트/어댑터 순수성), 제품에 하네스 패키지 없음. 공급자 어댑터가 수집·커넥터를 모른다는 것은 Gradle 모듈 의존
 * 선언과 checkModuleGraph가 강제한다(단계 4 T6).
 */
@AnalyzeClasses(packages = "io.scos", importOptions = TestLocations.ProductCode.class)
@DisplayName("제품 코드는 공개 경계 밖의 의존 규칙을 지킨다(ADR-0001·0012)")
class ArchitectureRulesTest {
  @ArchTest
  static final ArchRule sharedHasNoSpring =
      noClasses()
          .that()
          .resideInAnyPackage("io.scos.spi..", "io.scos.engine.support.text..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..");

  @ArchTest
  static final ArchRule recipesAndToolsKnowNoJdbc =
      noClasses()
          .that()
          .resideInAnyPackage("io.scos.engine..", "io.scos.mcp..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..");

  /** 저장 어댑터(Postgres*)는 포트 뒤에 있다 — 아는 것은 조립자(boot)뿐이다. */
  @ArchTest
  static final ArchRule adaptersStayBehindPorts =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "io.scos.store.postgres..",
              "io.scos.collection.internal.postgres..",
              "io.scos.boot..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.scos.store.postgres..", "io.scos.collection.internal.postgres..");

  /**
   * ADR-0001: stdout·stderr 직접 사용은 mcp 전송(System.in/out을 SDK에 넘기는 곳)과 조회 명령의 결과
   * 출력(server.CommandOutput, ADR-0024 §3)만 — 로그는 logback(stderr).
   */
  @ArchTest
  static final ArchRule noDirectStdStreamsOutsideMcp =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.scos.mcp..")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.CommandOutput")
          .should()
          .accessField(System.class, "out")
          .orShould()
          .accessField(System.class, "err");

  /** ADR-0006: 트랜잭션 경계는 TransactionTemplate으로 명시 — @Transactional 사용 0(저장소 밖·안 모두, 프록시 함정 차단). */
  @ArchTest
  static final ArchRule noTransactionalAnnotation =
      ArchRuleDefinition.noMethods()
          .should()
          .beAnnotatedWith(Transactional.class)
          .orShould()
          .beDeclaredInClassesThat()
          .areAnnotatedWith(Transactional.class);

  /** 테스트 클래스패스에는 하네스 소스셋이 있으므로 "하네스 밖 클래스가 하네스에 의존하지 않는다"로 쓴다(제품 bootJar에는 하네스 패키지 자체가 없다). */
  @ArchTest
  static final ArchRule noHarnessInProduct =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.scos.harness..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.scos.harness..");

  /** ADR-0022 §4: 공급자 호출 모듈은 그것을 쓰는 모듈(수집·공급자 어댑터·투영·검색·명령)을 모른다. */
  @ArchTest
  static final ArchRule providerCallKnowsNoConsumers =
      noClasses()
          .that()
          .resideInAPackage("io.scos.engine.support.call..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.scos.collection..",
              "io.scos.connector..",
              "io.scos.provider..",
              "io.scos.engine.internal..",
              "io.scos.engine.support.projection..",
              "io.scos.server..");
}
