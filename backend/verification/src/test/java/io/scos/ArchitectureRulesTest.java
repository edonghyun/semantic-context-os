package io.scos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
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
  static final ArchRule sourceDirectoryValuesAreIndependent =
      noClasses()
          .that()
          .resideInAPackage("io.scos.spi.source..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.scos.spi.connector..", "io.scos.spi.access..", "io.scos.spi.store..");

  @ArchTest
  static final ArchRule accessDoesNotDependOnConnectorPackage =
      noClasses()
          .that()
          .resideInAPackage("io.scos.spi.access..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.scos.spi.connector..");

  @ArchTest
  static final ArchRule sharedHasNoSpring =
      noClasses()
          .that()
          .resideInAnyPackage(
              "io.scos.spi..", "io.scos.engine.support.text..", "io.scos.engine.support.logging..")
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
   * ADR-0001: stdout·stderr 직접 사용은 mcp 전송(System.in/out을 SDK에 넘기는 곳 — 단독 클라이언트는 진입점
   * client.ClientMain)과 조회 명령의 결과 출력(server.CommandOutput, ADR-0024 §3)만 — 로그는
   * logback·slf4j-simple(stderr).
   */
  @ArchTest
  static final ArchRule noDirectStdStreamsOutsideMcp =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.scos.mcp..")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.CommandOutput")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.client.ClientMain")
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

  /** 읽기(검색)와 쓰기(투영)는 서로 import하지 않는다 — 검색은 투영 쪽을 모른다. */
  @ArchTest
  static final ArchRule searchDoesNotReachIngest =
      noClasses()
          .that()
          .resideInAPackage("io.scos.engine.internal.search..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.scos.engine.internal.projection..");

  /** 읽기(검색)와 쓰기(투영)는 서로 import하지 않는다 — 투영은 검색 쪽을 모른다. */
  @ArchTest
  static final ArchRule ingestDoesNotReachSearch =
      noClasses()
          .that()
          .resideInAPackage("io.scos.engine.internal.projection..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.scos.engine.internal.search..");

  /**
   * 설계 §4.3: 범위 없는 적재·유지보수 조회는 투영·재임베딩·import, 무제한 최신성, 운영 게이지·CLI와 조립·저장 구현(단독 클라이언트 조립
   * LocalEngine 포함 — 엔진·인제스터 조립과 "색인이 비었나" 확인)에만 허용한다. 직접 타입 참조·호출로 생기는 의존을 검사한다.
   * ContextIngestorBuilder는 공개 쓰기 표면 조립자다. McpHttpConfiguration은 다중 사용자 기동 검사에서만 전체 공간의 압축 유닛 수를
   * 읽는다. MemoryProjection·QuestionSearches는 testFixtures여서 가져오기 대상 밖이다.
   */
  @ArchTest
  static final ArchRule unitMaintenanceOnlyFromAllowedCallers =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "io.scos.engine.internal.projection..",
              "io.scos.boot..",
              "io.scos.store..",
              "io.scos.spi.store..")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.engine.internal.search.FreshnessReader")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.engine.ContextEngineBuilder")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.engine.ContextIngestorBuilder")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.DaemonConfiguration")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.OpsConfiguration")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.client.LocalEngine")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.McpHttpConfiguration")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.scos.spi.store.UnitMaintenance");

  /** 접근 범위의 무제한 전환은 단일 사용자 전략과 운영 출구만 소유한다. */
  @ArchTest
  static final ArchRule unrestrictedScopeOnlyFromAllowedCallers =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName("io.scos.engine.internal.access.UnrestrictedScopes")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.spi.store.AccessScope")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.DaemonGauges")
          .and()
          .doNotHaveFullyQualifiedName("io.scos.server.QueryCommands")
          .should()
          .callConstructor(AccessScope.Unrestricted.class, Principal.class)
          .orShould()
          .callMethod(AccessScope.class, "singleUser");

  /** 신호(점수 계수)는 순수 계산이다 — store 포트에 의존하지 않는다. */
  @ArchTest
  static final ArchRule signalsDoNotTouchStorePorts =
      noClasses()
          .that()
          .resideInAPackage("io.scos.engine.internal.search.signal..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.scos.spi.store.UnitStore")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.scos.spi.store.KeywordIndex")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.scos.spi.store.VectorIndex");
}
