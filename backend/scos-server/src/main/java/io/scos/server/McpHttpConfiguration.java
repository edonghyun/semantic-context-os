package io.scos.server;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.scos.boot.AccessProperties;
import io.scos.boot.McpConfiguration;
import io.scos.boot.McpConfiguration.McpProperties;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.boot.RerankProperties;
import io.scos.engine.AccessMode;
import io.scos.engine.ContextEngine;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpToolSpecs;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.UserTokens;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.Principal;
import io.scos.spi.store.UnitMaintenance;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

/**
 * 데몬 HTTP MCP. 검색은 쓰기 풀이 아닌 별도 읽기 전용 풀로만 읽는다. 읽기 풀을 DataSource 빈으로 내놓으면 Boot DataSource 자동 구성이
 * 물러나므로 별도 자원 빈이 소유한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("daemon")
@ConditionalOnProperty(prefix = "scos.mcp.http", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpHttpConfiguration.McpHttpProperties.class)
class McpHttpConfiguration {
  static final String PATH = "/mcp";
  static final int MIN_TOKEN_LENGTH = 32;
  private static final int DEFAULT_SERVER_PORT = 8080;

  McpHttpConfiguration(McpHttpProperties http, AccessProperties access, Environment environment) {
    String filterMode =
        switch (access.mode()) {
          case SINGLE_USER -> "single-user";
          case MULTI_USER -> "multi-user";
        };
    if (!filterMode.equalsIgnoreCase(environment.getProperty("scos.access.mode", "single-user")))
      throw new IllegalStateException("scos.access.mode와 HTTP 필터 조립 모드가 일치해야 합니다");
    switch (access.mode()) {
      case SINGLE_USER -> http.requireSharedToken();
      case MULTI_USER ->
          AccessModeChecks.refuseUnsafeManagement(
              environment.getProperty("server.port", Integer.class, DEFAULT_SERVER_PORT),
              environment.getProperty("management.server.port", Integer.class),
              environment.getProperty("management.server.address"));
    }
  }

  @ConfigurationProperties("scos.mcp.http")
  record McpHttpProperties(
      boolean enabled,
      String token,
      int readPoolSize,
      Duration connectionTimeout,
      List<String> allowedHosts,
      List<String> allowedOrigins) {
    McpHttpProperties(
        boolean enabled,
        @Nullable String token,
        @DefaultValue("4") int readPoolSize,
        @DefaultValue("5s") Duration connectionTimeout,
        @Nullable List<String> allowedHosts,
        @Nullable List<String> allowedOrigins) {
      this.enabled = enabled;
      this.token = token == null ? "" : token.strip();
      this.readPoolSize = readPoolSize;
      this.connectionTimeout = connectionTimeout;
      this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
      this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
      if (readPoolSize < 1) throw new IllegalStateException("scos.mcp.http.read-pool-size ≥ 1");
    }

    void requireSharedToken() {
      if (token.length() < MIN_TOKEN_LENGTH)
        throw new IllegalStateException(
            "scos.mcp.http.enabled=true 이면 scos.mcp.http.token(SCOS_MCP_HTTP_TOKEN)이 "
                + MIN_TOKEN_LENGTH
                + "자 이상이어야 합니다");
      if (!token.chars().allMatch(c -> c >= '!' && c <= '~'))
        throw new IllegalStateException(
            "scos.mcp.http.token(SCOS_MCP_HTTP_TOKEN)은 보이는 ASCII만 쓸 수 있습니다(헤더로 보낸다)");
    }

    /** 토큰은 싣지 않는다 — 로그·바인딩 실패 메시지·예외로 새지 않게. */
    @Override
    public String toString() {
      return "McpHttpProperties[enabled="
          + enabled
          + ", token=***, readPoolSize="
          + readPoolSize
          + ", connectionTimeout="
          + connectionTimeout
          + "]";
    }
  }

  @Bean
  SmartInitializingSingleton mcpHttpCompactedCheck(AccessProperties access, UnitMaintenance units) {
    return () -> {
      if (access.mode() == AccessProperties.Mode.MULTI_USER)
        AccessModeChecks.refuseCompacted(access, units.countCompacted());
    };
  }

  /** HTTP의 실제 엔진을 검사·전송이 함께 쓴다. 기존 명령용 기본 엔진의 주입 대상은 유지한다. */
  @Bean(defaultCandidate = false)
  ContextEngine mcpHttpEngine(
      ReadResources resources,
      McpProperties mcp,
      AccessProperties access,
      DomainPolicy domains,
      RerankProperties rerank,
      ObjectProvider<TextGenerator> generators,
      Embedder embedder,
      Clock clock,
      ObjectMapper mapper,
      MeterRegistry registry) {
    HikariDataSource read = resources.read();
    ExecutorService lexicalExecutor = resources.lexicalExecutor();
    McpConfiguration assembly = new McpConfiguration();
    return assembly.buildContextEngine(
        mcp,
        rerank,
        generators.getIfAvailable(),
        PostgresIndexConfiguration.newUnitStore(read),
        assembly.accessParts(access, read, domains),
        PostgresIndexConfiguration.newKeywordIndex(read),
        PostgresIndexConfiguration.newVectorIndex(read),
        embedder,
        clock,
        mapper,
        registry,
        lexicalExecutor);
  }

  @Bean(destroyMethod = "close")
  McpHttpEndpoint mcpHttpEndpoint(
      @Qualifier("mcpHttpEngine") ContextEngine engine,
      AccessProperties access,
      ObjectMapper mapper,
      QueryLog queryLog) {
    return McpHttpEndpoint.create(
        new McpTools(engine, mapper, queryLog),
        PATH,
        errorExposure(access),
        contextExtractor(access),
        () -> {});
  }

  @Bean(destroyMethod = "close")
  // 읽기 자원은 소비 빈이 요구할 때 열어, 외부 자원을 대체한 Spring 슬라이스도 풀 없이 조립한다.
  @Lazy
  ReadResources mcpHttpReadResources(
      DataSourceProperties db, McpHttpProperties http, MeterRegistry registry) {
    return new ReadResources(
        lexicalExecutor(http.readPoolSize()), readOnlyPool(db, http, registry));
  }

  static ErrorExposure errorExposure(AccessProperties access) {
    return access.mode() == AccessProperties.Mode.MULTI_USER
        ? ErrorExposure.MASKED
        : ErrorExposure.RAW;
  }

  static McpTransportContextExtractor<HttpServletRequest> contextExtractor(
      AccessProperties access) {
    if (access.mode() == AccessProperties.Mode.SINGLE_USER)
      return request ->
          McpTransportContext.create(Map.of(McpToolSpecs.PRINCIPAL_KEY, Principal.singleUser()));
    return request -> {
      Object attribute = request.getAttribute(PrincipalBearerFilter.PRINCIPAL_ATTRIBUTE);
      return attribute instanceof Principal principal
          ? McpTransportContext.create(Map.of(McpToolSpecs.PRINCIPAL_KEY, principal))
          : McpTransportContext.EMPTY;
    };
  }

  /** Spring이 끝점 뒤에 닫는 읽기 자원. 조립 실패 때도 실행기를 먼저 멈추고 풀을 닫는다. */
  record ReadResources(ExecutorService lexicalExecutor, HikariDataSource read)
      implements AutoCloseable {
    @Override
    public void close() {
      lexicalExecutor.shutdownNow();
      read.close();
    }
  }

  /** stdio용 2스레드 실행기와 달리 동시 요청을 받으므로 읽기 풀 크기만큼. */
  static ExecutorService lexicalExecutor(int threads) {
    AtomicInteger n = new AtomicInteger();
    return Executors.newFixedThreadPool(
        threads,
        r -> {
          Thread t = new Thread(r, "mcp-http-lexical-" + n.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }

  static HikariDataSource readOnlyPool(
      DataSourceProperties db, McpHttpProperties http, MeterRegistry registry) {
    HikariDataSource ds = db.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    ds.setPoolName("scos-mcp-read");
    ds.setReadOnly(true);
    ds.setConnectionInitSql("SET default_transaction_read_only = on");
    ds.setMaximumPoolSize(http.readPoolSize());
    ds.setMinimumIdle(1);
    ds.setConnectionTimeout(http.connectionTimeout().toMillis());
    ds.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
    return ds;
  }

  @Bean
  ServletRegistrationBean<HttpServlet> mcpHttpServlet(
      McpHttpEndpoint endpoint,
      AccessProperties access,
      @Qualifier("mcpHttpEngine") ContextEngine engine) {
    if (access.mode() == AccessProperties.Mode.MULTI_USER
        && engine.accessMode() == AccessMode.UNRESTRICTED)
      throw new IllegalStateException("multi-user HTTP와 UNRESTRICTED 엔진을 함께 조립할 수 없습니다");
    ServletRegistrationBean<HttpServlet> bean =
        new ServletRegistrationBean<>(endpoint.servlet(), PATH);
    bean.setName("scosMcpHttp");
    bean.setAsyncSupported(true);
    return bean;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "scos.access",
      name = "mode",
      havingValue = "single-user",
      matchIfMissing = true)
  FilterRegistrationBean<LocalBearerFilter> mcpHttpFilter(McpHttpProperties http) {
    FilterRegistrationBean<LocalBearerFilter> bean =
        new FilterRegistrationBean<>(new LocalBearerFilter(http.token()));
    bean.addUrlPatterns(PATH);
    bean.setName("scosMcpHttpAuth");
    return bean;
  }

  @Bean
  @ConditionalOnProperty(prefix = "scos.access", name = "mode", havingValue = "multi-user")
  FilterRegistrationBean<AllowedHosts> mcpHttpAllowedHosts(McpHttpProperties http) {
    var bean =
        new FilterRegistrationBean<>(new AllowedHosts(http.allowedHosts(), http.allowedOrigins()));
    bean.addUrlPatterns(PATH);
    bean.setName("scosMcpHttpHosts");
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return bean;
  }

  @Bean
  @ConditionalOnProperty(prefix = "scos.access", name = "mode", havingValue = "multi-user")
  UserTokens mcpHttpUserTokens(ReadResources resources, DataSource write) {
    return new PostgresIndexConfiguration().newUserTokens(resources.read(), write);
  }

  @Bean
  @ConditionalOnProperty(prefix = "scos.access", name = "mode", havingValue = "multi-user")
  ThreadPoolTaskScheduler mcpTokenUsageScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("mcp-token-usage-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    // 기록기가 원인을 이미 보고했다. 주기는 계속하고 종료 반영의 예외는 호출자에게 전파한다.
    scheduler.setErrorHandler(failure -> {});
    return scheduler;
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "scos.access", name = "mode", havingValue = "multi-user")
  TokenUsageRecorder mcpTokenUsage(
      @Qualifier("mcpHttpUserTokens") UserTokens tokens,
      @Qualifier("mcpTokenUsageScheduler") TaskScheduler scheduler) {
    return new TokenUsageRecorder(tokens, scheduler);
  }

  @Bean
  @ConditionalOnProperty(prefix = "scos.access", name = "mode", havingValue = "multi-user")
  FilterRegistrationBean<PrincipalBearerFilter> mcpHttpPrincipalFilter(
      @Qualifier("mcpHttpUserTokens") UserTokens tokens, TokenUsageRecorder usage, Clock clock) {
    var bean = new FilterRegistrationBean<>(new PrincipalBearerFilter(tokens, usage, clock));
    bean.addUrlPatterns(PATH);
    bean.setName("scosMcpHttpAuth");
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return bean;
  }
}
