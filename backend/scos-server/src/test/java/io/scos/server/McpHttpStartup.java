package io.scos.server;

import static io.scos.server.TestMcpHttpServer.SINGLE_USER;

import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import io.scos.boot.McpConfiguration.McpProperties;
import io.scos.engine.ContextEngine;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpStdioServer;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.access.TokenHash;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.store.memory.ObservedUserTokens;
import io.scos.support.LogCapture;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.TaskUtils;

/** DB·소켓을 열지 않고 실제 기동 검사 빈을 실행하는 Spring 슬라이스. */
final class McpHttpStartup implements AutoCloseable {
  private static final int EMBEDDING_DIMENSIONS = 2;
  private static final SpaceId OTHER_SPACE = new SpaceId("other");
  private final StartupUnitMaintenance units =
      new StartupUnitMaintenance(
          new MemoryUnitMaintenance(new MemoryUnitStore(), new MemoryVectorIndex()));
  private final McpHttpEndpoint endpoint =
      McpHttpEndpoint.create(
          McpHttpEndpointTest.memoryTools(),
          McpHttpConfiguration.PATH,
          ErrorExposure.RAW,
          McpHttpConfiguration.contextExtractor(SINGLE_USER),
          () -> {});
  private final ApplicationContextRunner runner;
  private final AtomicBoolean failUsageWrite = new AtomicBoolean();
  private final ObservedUserTokens tokens = new ObservedUserTokens(this::beforeUsageWrite);
  private Throwable failure;
  private ContextEngine engine;

  McpHttpStartup(Mode mode) {
    engine = engineFor(mode);
    tokens.seed(AccessDirectory.empty().withUser(new User("alice", "alice@example.com", true)));
    tokens.store(
        TokenHash.of(PrincipalBearerScenario.TOKEN), "alice", "http", PrincipalBearerScenario.NOW);
    runner =
        new ApplicationContextRunner()
            .withUserConfiguration(McpHttpConfiguration.class)
            .withPropertyValues(
                McpHttpConfigurationTest.DAEMON_PROFILE,
                McpHttpConfigurationTest.ENABLED,
                McpHttpConfigurationTest.VALID_TOKEN,
                "scos.access.mode=" + (mode == Mode.MULTI_USER ? "multi-user" : "single-user"),
                "server.port=8081",
                "management.server.port=8082",
                "management.server.address=127.0.0.1")
            .withBean(
                AccessProperties.class, () -> new AccessProperties(mode, null, List.of(), Map.of()))
            .withBean(UnitMaintenance.class, () -> units)
            .withBean(Clock.class, () -> Clock.fixed(PrincipalBearerScenario.NOW, ZoneOffset.UTC))
            // 설정 정의를 읽은 뒤 놓아, 빈 덮어쓰기 허용 없이 외부 자원 조립만 대체한다.
            .withBean(BeanFactoryPostProcessor.class, this::externalResources);
  }

  private BeanFactoryPostProcessor externalResources() {
    return beanFactory -> {
      beanFactory.registerSingleton("mcpHttpEndpoint", endpoint);
      beanFactory.registerSingleton("mcpHttpEngine", engine);
      beanFactory.registerSingleton("mcpHttpUserTokens", tokens);
    };
  }

  private ContextEngine engineFor(Mode mode) {
    var store = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    var builder =
        ContextEngine.builder()
            .units(store)
            .vectors(vectors)
            .keywords(new MemoryKeywordIndex())
            .embedder(FixedEmbedder.zero(EMBEDDING_DIMENSIONS));
    if (mode == Mode.MULTI_USER)
      builder.access(
          new MemoryIdentityStore(),
          new RecordedSourceFreshness(),
          new DomainPolicy(Set.of(), Map.of()));
    else builder.unrestricted(new MemoryUnitMaintenance(store, vectors));
    return builder.build();
  }

  void givenUnrestrictedEngine() {
    engine = engineFor(Mode.SINGLE_USER);
  }

  void givenCompactedInAnotherSpace() {
    units.upsert(OTHER_SPACE, List.of(ContextUnits.unit("summary").compacted().build()));
    if (units.listAll(OTHER_SPACE).size() != 1)
      throw new IllegalStateException("given 준비 실패: 다른 공간에 압축 유닛이 있어야 한다");
  }

  int compactedReads() {
    return units.compactedReads();
  }

  Throwable start() {
    return start(runner);
  }

  Throwable startWith(String... properties) {
    return start(runner.withPropertyValues(properties));
  }

  Throwable startWithRawMode(String mode) {
    return start(
        runner.withInitializer(
            ctx ->
                ctx.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new MapPropertySource(
                            "raw-http-access-mode", Map.of("scos.access.mode", mode)))));
  }

  LogCapture schedulerErrors() {
    return LogCapture.of(TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.getClass());
  }

  void givenUsageWriteFailure() {
    failUsageWrite.set(true);
  }

  private void beforeUsageWrite() {
    if (failUsageWrite.getAndSet(false))
      throw new IllegalStateException("http-usage-write-fixture");
  }

  void flushScheduledUsage() {
    runner.run(
        ctx -> {
          if (ctx.getStartupFailure() != null)
            throw new IllegalStateException(
                "given 준비 실패: 사용 기록기가 조립되어야 한다", ctx.getStartupFailure());
          ctx.getBean(TokenUsageRecorder.class)
              .record(TokenHash.of(PrincipalBearerScenario.TOKEN), PrincipalBearerScenario.NOW);
          runScheduledFlush(ctx);
        });
  }

  private void runScheduledFlush(AssertableApplicationContext ctx) {
    var scheduler = ctx.getBean("mcpTokenUsageScheduler", ThreadPoolTaskScheduler.class);
    // 실제 예약 래퍼와 ErrorHandler를 통과시키되 1분을 기다리지 않는다.
    var queue = scheduler.getScheduledThreadPoolExecutor().getQueue();
    var scheduled = queue.peek();
    if (scheduled == null || !queue.remove(scheduled))
      throw new IllegalStateException("given 준비 실패: 주기 반영 작업이 예약되어야 한다");
    scheduled.run();
  }

  Optional<Instant> retryUsageOnNextTick() {
    var persisted = new AtomicReference<Optional<Instant>>(Optional.empty());
    runner.run(
        ctx -> {
          if (ctx.getStartupFailure() != null)
            throw new IllegalStateException(
                "given 준비 실패: 사용 기록기가 조립되어야 한다", ctx.getStartupFailure());
          ctx.getBean(TokenUsageRecorder.class)
              .record(TokenHash.of(PrincipalBearerScenario.TOKEN), PrincipalBearerScenario.NOW);
          runScheduledFlush(ctx);
          runScheduledFlush(ctx);
          persisted.set(lastUsed());
        });
    return persisted.get();
  }

  Optional<Instant> lastUsed() {
    return tokens.lastUsedAt(TokenHash.of(PrincipalBearerScenario.TOKEN));
  }

  Reply authorize(String host, String authorization, String... properties) {
    var headers = new HashMap<String, String>();
    headers.put("Host", host);
    if (authorization != null) headers.put("Authorization", authorization);
    return authorize(headers, properties);
  }

  Reply authorizeFromOrigin(String origin) {
    return authorize(
        Map.of(
            "Host",
            AllowedHostsScenario.HOST,
            "Authorization",
            "Bearer " + PrincipalBearerScenario.TOKEN,
            "Origin",
            origin));
  }

  private Reply authorize(Map<String, String> headers, String... properties) {
    var reply = new AtomicReference<Reply>();
    runner
        .withPropertyValues(
            "scos.mcp.http.token=",
            "scos.mcp.http.allowed-hosts=" + AllowedHostsScenario.HOST,
            "scos.mcp.http.allowed-origins=" + AllowedHostsScenario.ORIGIN)
        .withPropertyValues(properties)
        .run(ctx -> reply.set(filterReply(ctx, headers)));
    return reply.get();
  }

  private Reply filterReply(AssertableApplicationContext ctx, Map<String, String> headers)
      throws Exception {
    if (ctx.getStartupFailure() != null)
      throw new IllegalStateException("given 준비 실패: HTTP 필터가 조립되어야 한다", ctx.getStartupFailure());
    Filter[] filters =
        ctx.getBeansOfType(FilterRegistrationBean.class).values().stream()
            .sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
            .map(FilterRegistrationBean::getFilter)
            .toArray(Filter[]::new);
    var request = request(headers);
    var response = new MockHttpServletResponse();
    new MockFilterChain(endpoint.servlet(), filters).doFilter(request, response);
    return new Reply(
        response.getStatus(),
        response.getContentAsString(),
        Optional.ofNullable(request.getAttribute(PrincipalBearerFilter.PRINCIPAL_ATTRIBUTE)));
  }

  private MockHttpServletRequest request(Map<String, String> headers) {
    var request = new MockHttpServletRequest("POST", "/mcp");
    headers.forEach(request::addHeader);
    request.addHeader("Accept", "application/json, text/event-stream");
    request.setContentType("application/json");
    request.setContent(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
            .getBytes(StandardCharsets.UTF_8));
    return request;
  }

  record Reply(int status, String body, Optional<Object> principal) {}

  Throwable startWithStdio() {
    return start(
        runner
            .withUserConfiguration(McpRuntimeConfiguration.class)
            .withPropertyValues("spring.profiles.active=mcp,daemon")
            .withBean(McpProperties.class, () -> new McpProperties("legacy", null))
            .withBean(SpaceMeta.class, MemorySpaceMeta::new)
            .withBean(Embedder.class, () -> FixedEmbedder.zero(EMBEDDING_DIMENSIONS))
            .withBean(
                McpStdioServer.class, () -> new McpStdioServer(McpHttpEndpointTest.memoryTools())));
  }

  private Throwable start(ApplicationContextRunner context) {
    context.run(ctx -> failure = ctx.getStartupFailure());
    return failure;
  }

  @Override
  public void close() throws Exception {
    endpoint.close();
  }
}
