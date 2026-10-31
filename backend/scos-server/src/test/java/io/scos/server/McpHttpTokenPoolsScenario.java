package io.scos.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import io.scos.boot.McpConfiguration.McpProperties;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.boot.RerankProperties;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.mcp.QueryLog;
import io.scos.server.McpHttpConfiguration.ReadResources;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.TokenHash;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.postgres.PostgresAccessSeed;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL 두 연결과 HTTP 자원 빈의 인증·종료 반영을 관찰한다. */
@Tag("db")
final class McpHttpTokenPoolsScenario implements AutoCloseable {
  private static final int DIMENSIONS = 2;
  private static final TokenHash TOKEN = TokenHash.of(PrincipalBearerScenario.TOKEN);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  McpHttpTokenPoolsScenario() {
    PostgresSupport.truncateAll();
    new PostgresAccessSeed(PostgresSupport.dataSource())
        .seed(AccessDirectory.empty().withUser(new User("alice", "alice@example.com", true)));
    new PostgresIndexConfiguration()
        .newUserTokens(PostgresSupport.dataSource(), PostgresSupport.dataSource())
        .store(TOKEN, "alice", "http", PrincipalBearerScenario.NOW);
  }

  private ApplicationContextRunner runner() {
    return infrastructure()
        .withUserConfiguration(McpHttpConfiguration.class)
        .withPropertyValues(
            "spring.profiles.active=daemon",
            "scos.mcp.http.enabled=true",
            "scos.access.mode=multi-user",
            "management.server.port=8082",
            "management.server.address=127.0.0.1",
            "server.port=8081")
        .withBean(
            AccessProperties.class, () -> new AccessProperties(Mode.MULTI_USER, null, null, null))
        .withBean(McpProperties.class, () -> new McpProperties("legacy", null))
        .withBean(
            RerankProperties.class, () -> new RerankProperties("none", null, null, null, null))
        .withBean(Embedder.class, () -> FixedEmbedder.zero(DIMENSIONS))
        .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
        .withBean(QueryLog.class, () -> QueryLog.NONE);
  }

  private ApplicationContextRunner infrastructure() {
    return new ApplicationContextRunner()
        .withBean(DataSource.class, PostgresSupport::dataSource)
        .withBean(DataSourceProperties.class, this::databaseProperties)
        .withBean(
            UnitMaintenance.class,
            () -> new PostgresIndexConfiguration().newUnitMaintenance(PostgresSupport.dataSource()))
        .withBean(MeterRegistry.class, () -> registry)
        .withBean(Clock.class, () -> Clock.fixed(PrincipalBearerScenario.NOW, ZoneOffset.UTC));
  }

  private DataSourceProperties databaseProperties() {
    var properties = new DataSourceProperties();
    properties.setUrl(PostgresSupport.jdbcUrl());
    properties.setUsername(PostgresSupport.username());
    properties.setPassword(PostgresSupport.password());
    return properties;
  }

  Reply authenticateAndClose() {
    var principal = new AtomicReference<Optional<Object>>(Optional.empty());
    var resources = new AtomicReference<ReadResources>();
    var acquired = new AtomicLong();
    runner()
        .run(
            ctx -> {
              if (ctx.getStartupFailure() != null)
                throw new IllegalStateException(
                    "given 준비 실패: 실제 HTTP 자원이 조립되어야 한다", ctx.getStartupFailure());
              resources.set(ctx.getBean(ReadResources.class));
              var filter =
                  ctx.getBean("mcpHttpPrincipalFilter", FilterRegistrationBean.class).getFilter();
              var request = new MockHttpServletRequest("POST", "/mcp");
              request.addHeader("Authorization", "Bearer " + PrincipalBearerScenario.TOKEN);
              filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
              principal.set(
                  Optional.ofNullable(
                      request.getAttribute(PrincipalBearerFilter.PRINCIPAL_ATTRIBUTE)));
              acquired.set(readAcquisitions());
            });
    return new Reply(
        principal.get(),
        acquired.get(),
        lastUsed(),
        resources.get().read().isClosed(),
        resources.get().lexicalExecutor().isShutdown());
  }

  private long readAcquisitions() {
    return Optional.ofNullable(
            registry.find("hikaricp.connections.acquire").tag("pool", "scos-mcp-read").timer())
        .map(Timer::count)
        .orElse(0L);
  }

  private Optional<Instant> lastUsed() {
    return JdbcClient.create(PostgresSupport.dataSource())
        .sql("SELECT last_used_at FROM user_tokens WHERE token_hash = :hash")
        .param("hash", TOKEN.value())
        .query(
            (row, index) ->
                Optional.ofNullable(row.getTimestamp("last_used_at")).map(t -> t.toInstant()))
        .single();
  }

  record Reply(
      Optional<Object> principal,
      long readAcquisitions,
      Optional<Instant> lastUsed,
      boolean readClosed,
      boolean lexicalClosed) {}

  @Override
  public void close() {
    registry.close();
  }
}
