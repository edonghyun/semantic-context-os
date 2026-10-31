package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("HTTP MCP 조립은 daemon 프로필에서 켰을 때만 동작한다")
class McpHttpConfigurationTest {
  static final String DAEMON_PROFILE = "spring.profiles.active=daemon";
  static final String ENABLED = "scos.mcp.http.enabled=true";
  static final String VALID_TOKEN =
      "scos.mcp.http.token=" + "v".repeat(McpHttpConfiguration.MIN_TOKEN_LENGTH);
  static final String SHORT_TOKEN = "scos.mcp.http.token=short";
  static final String TOKEN_ENV = "SCOS_MCP_HTTP_TOKEN";

  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(McpHttpConfiguration.class)
          .withBean(
              AccessProperties.class,
              () -> new AccessProperties(Mode.SINGLE_USER, null, List.of(), Map.of()))
          .withBean(
              UnitMaintenance.class,
              () -> new MemoryUnitMaintenance(new MemoryUnitStore(), new MemoryVectorIndex()));

  @Test
  @DisplayName("기본(꺼짐)이면 조립하지 않는다")
  void offByDefault() {
    runner
        .withPropertyValues(DAEMON_PROFILE)
        // then
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(McpHttpConfiguration.class));
  }

  @Test
  @DisplayName("daemon 프로필이 아니면 켜도 조립하지 않는다")
  void onlyInDaemon() {
    runner
        .withPropertyValues(ENABLED, VALID_TOKEN)
        // then
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(McpHttpConfiguration.class));
  }

  @Test
  @DisplayName("켰는데 토큰이 짧으면 환경 변수를 짚어 기동에 실패한다")
  void shortTokenFailsStartup() {
    runner
        .withPropertyValues(DAEMON_PROFILE, ENABLED, SHORT_TOKEN)
        // then
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining(TOKEN_ENV));
  }

  @Test
  @DisplayName("두 프로필의 다중 사용자 기동은 stdio 금지를 안내한다")
  void combinedProfilesRefuseStdioWithGuidance() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWithStdio();

      // then
      assertThat(failure).hasMessageContaining("stdio MCP를 열 수 없습니다", "데몬 HTTP MCP");
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 다른 공간의 압축 유닛이 있어도 기동을 거부한다")
  void httpRefusesCompactedInAnotherSpace() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      http.givenCompactedInAnotherSpace();

      Throwable failure = http.start();

      // then
      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("압축 유닛 1개");
    }
  }

  @Test
  @DisplayName("압축 유닛이 없는 다중 사용자 HTTP는 기동한다")
  void httpAllowsNoCompacted() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.start();

      // then
      assertThat(failure).isNull();
    }
  }

  @Test
  @DisplayName("단일 사용자 HTTP는 압축 개수를 조회하지 않고 기동한다")
  void singleUserStartsWithoutCountingCompacted() throws Exception {
    try (var http = new McpHttpStartup(Mode.SINGLE_USER)) {
      http.givenCompactedInAnotherSpace();

      Throwable failure = http.start();

      // then
      assertThat(failure).isNull();
      assertThat(http.compactedReads()).isZero();
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 공유 토큰 없이 기동한다")
  void multiUserNeedsNoSharedToken() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWith("scos.mcp.http.token=");

      // then
      assertThat(failure).isNull();
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 앱·관리 포트가 모두 임의 포트이면 기동한다")
  void randomManagementPortAllowsStartup() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWith("server.port=0", "management.server.port=0");

      // then
      assertThat(failure).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "8081"})
  @DisplayName("다중 사용자 HTTP는 관리 포트가 없거나 앱 포트와 같으면 기동을 거부한다")
  void managementPortMustBeSeparate(String port) throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWith("management.server.port=" + port);

      // then
      assertThat(failure)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("management.server.port");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0.0.0.0", "192.0.2.1", "localhost", "::"})
  @DisplayName("다중 사용자 HTTP는 관리 주소가 루프백 리터럴이 아니면 기동을 거부한다")
  void managementAddressMustBeLoopback(String address) throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWith("management.server.address=" + address);

      // then
      assertThat(failure)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("management.server.address");
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 조립된 엔진이 무제한이면 기동을 거부한다")
  void unrestrictedEngineRefusesMultiUserHttp() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      http.givenUnrestrictedEngine();

      Throwable failure = http.start();

      // then
      assertThat(failure)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("UNRESTRICTED");
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 사용자 토큰으로 principal을 인증하는 필터를 등록한다")
  void multiUserRegistersPrincipalFilter() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply =
          http.authorize(AllowedHostsScenario.HOST, "Bearer " + PrincipalBearerScenario.TOKEN);

      // then
      assertThat(reply.status()).isEqualTo(200);
      assertThat(reply.principal()).contains(PrincipalBearerScenario.ALICE);
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 인증보다 먼저 비허용 Host를 거부한다")
  void hostFilterPrecedesAuthentication() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply = http.authorize("unlisted.example.com:8081", null);

      // then
      assertThat(reply.status()).isEqualTo(403);
      assertThat(reply.body()).isEqualTo("{\"error\":\"forbidden\"}");
    }
  }

  @Test
  @DisplayName("단일 사용자 HTTP는 공유 토큰이 없으면 기동을 거부한다")
  void singleUserRequiresSharedToken() throws Exception {
    try (var http = new McpHttpStartup(Mode.SINGLE_USER)) {
      Throwable failure = http.startWith("scos.mcp.http.token=");

      // then
      assertThat(failure)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("SCOS_MCP_HTTP_TOKEN");
    }
  }

  @Test
  @DisplayName("다중 사용자 HTTP는 관리 주소가 IPv6 루프백이면 기동한다")
  void ipv6LoopbackAllowsStartup() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWith("management.server.address=::1");

      // then
      assertThat(failure).isNull();
    }
  }

  @Test
  @DisplayName("조립된 필터는 Host 목록이 비면 유효 토큰도 거부한다")
  void assembledEmptyHostsRefuseTokens() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply =
          http.authorize(
              AllowedHostsScenario.HOST,
              "Bearer " + PrincipalBearerScenario.TOKEN,
              "scos.mcp.http.allowed-hosts=");

      // then
      assertThat(reply.status()).isEqualTo(403);
      assertThat(reply.principal()).isEmpty();
    }
  }

  @Test
  @DisplayName("조립된 필터는 허용 Host의 잘못된 토큰을 고정 401로 거부한다")
  void assembledInvalidTokenIsUnauthorized() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply = http.authorize(AllowedHostsScenario.HOST, "Bearer invalid-fixture");

      // then
      assertThat(reply.status()).isEqualTo(401);
      assertThat(reply.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    }
  }

  @Test
  @DisplayName("조립된 필터는 목록 밖 Origin을 거부한다")
  void assembledUnlistedOriginIsForbidden() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply = http.authorizeFromOrigin("https://unlisted.example.com");

      // then
      assertThat(reply.status()).isEqualTo(403);
      assertThat(reply.body()).isEqualTo("{\"error\":\"forbidden\"}");
    }
  }

  @Test
  @DisplayName("조립된 필터는 목록 안 Origin의 사용자 토큰을 통과시킨다")
  void assembledListedOriginIsAccepted() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply = http.authorizeFromOrigin(AllowedHostsScenario.ORIGIN);

      // then
      assertThat(reply.status()).isEqualTo(200);
      assertThat(reply.principal()).contains(PrincipalBearerScenario.ALICE);
    }
  }

  @ParameterizedTest
  @CsvSource({"SINGLE_USER, multi-user", "MULTI_USER, single-user"})
  @DisplayName("접근 모드와 필터 설정이 다르면 HTTP 기동을 거부한다")
  void filterModeMustMatchAccessMode(Mode mode, String filterMode) throws Exception {
    try (var http = new McpHttpStartup(mode)) {
      Throwable failure = http.startWith("scos.access.mode=" + filterMode);

      // then
      assertThat(failure)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("필터 조립 모드");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"MULTI_USER", "multi_user", " multi-user ", ""})
  @DisplayName("필터를 선택할 수 없는 모드 표기는 HTTP 기동을 거부한다")
  void unselectableFilterModeRefusesStartup(String filterMode) throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      Throwable failure = http.startWithRawMode(filterMode);

      // then
      assertThat(failure)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("필터 조립 모드");
    }
  }

  @Test
  @DisplayName("정규 모드 표기의 대소문자는 필터 선택에 영향을 주지 않는다")
  void canonicalModeIsCaseInsensitive() throws Exception {
    try (var http = new McpHttpStartup(Mode.MULTI_USER)) {
      var reply =
          http.authorize(
              AllowedHostsScenario.HOST,
              "Bearer " + PrincipalBearerScenario.TOKEN,
              "scos.access.mode=MULTI-USER");

      // then
      assertThat(reply.principal()).contains(PrincipalBearerScenario.ALICE);
    }
  }
}
