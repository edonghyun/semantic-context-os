package io.scos.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.scos.boot.AccessProperties;
import io.scos.boot.AdminConfiguration;
import io.scos.engine.ContextEngine;
import io.scos.engine.admin.AdminDiagnostics;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.support.logging.JsonLineEncoder;
import io.scos.mcp.ErrorExposure;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.access.UserTokens;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryAdminScenario;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.store.memory.ObservedUserTokens;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 관리 포트와 운영 HTTP 배선을 연결하고 저장 경계만 선택한다. */
final class AdminHttpScenario implements AutoCloseable {
  static final String SHARED = "synthetic-shared-mcp-token-32-characters";
  static final String SENTINEL = "scos_synthetic-secret-exception-sentinel";
  static final Principal ADMIN = new Principal("admin");
  static final Principal MEMBER = new Principal("member");
  static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final DomainPolicy DOMAINS = new DomainPolicy(Set.of("example.com"), Map.of());
  private final ConfigurableApplicationContext context;
  private final AdminPort port;
  private final AtomicBoolean fail = new AtomicBoolean();
  private final AtomicBoolean fatal = new AtomicBoolean();
  private final HttpClient client = HttpClient.newHttpClient();
  private final MockMvc mvc;
  private final String adminToken;
  private final String memberToken;
  private final int appPort;
  private final int managementPort;
  private boolean network;
  private final boolean multi;
  private final Optional<Database> authenticationDatabase;

  AdminHttpScenario(boolean multi) throws Exception {
    this(multi, new MemoryAdminScenario());
  }

  private AdminHttpScenario(boolean multi, MemoryAdminScenario storage) throws Exception {
    this(multi, storage.store(), storage.authentication());
    storage.registerSource(new SourceAccount(new SourceInstanceId("notion-a"), "notion"));
  }

  private AdminHttpScenario(boolean multi, AdminStore store, UserTokens tokens) throws Exception {
    this(multi, store, Optional.of(tokens), Optional.empty());
  }

  AdminHttpScenario(boolean multi, AdminStore store, Database database) throws Exception {
    this(multi, store, Optional.empty(), Optional.of(database));
    network = true;
  }

  private AdminHttpScenario(
      boolean multi, AdminStore store, Optional<UserTokens> tokens, Optional<Database> database)
      throws Exception {
    this.multi = multi;
    authenticationDatabase = database;
    seed(store);
    AdminStore boundary =
        new AdminStore() {
          @Override
          public <T> T transact(Function<Session, T> work) {
            if (fatal.get()) throw new AssertionError(SENTINEL);
            if (fail.get()) throw new IllegalStateException(SENTINEL);
            return store.transact(work);
          }
        };
    port = AdminConfiguration.newAdminPort(boundary, CLOCK, DOMAINS, diagnostics());
    adminToken = port.issueToken(ADMIN, "admin", "admin-http").value();
    memberToken = port.issueToken(ADMIN, "member", "member-http").value();
    appPort = availablePort();
    managementPort = availablePort();
    context = start(multi, tokens);
    mvc = mockMvc();
  }

  private void seed(AdminStore store) {
    store.transact(
        session -> {
          session.insertUser(new UserAccount("admin", "admin@example.com", true, Role.ADMIN, NOW));
          session.insertUser(
              new UserAccount("member", "member@example.com", true, Role.MEMBER, NOW));
          return true;
        });
  }

  private ConfigurableApplicationContext start(boolean multi, Optional<UserTokens> tokens) {
    var application = new SpringApplication(HttpApplication.class);
    application.setRegisterShutdownHook(false);
    // HTTP 슬라이스는 JVM 전역 logback을 다시 구성하지 않는다.
    application.setListeners(
        application.getListeners().stream()
            .filter(listener -> !(listener instanceof LoggingApplicationListener))
            .toList());
    application.setDefaultProperties(httpProperties(multi));
    application.addInitializers(
        ctx -> ctx.addBeanFactoryPostProcessor(externalResources(multi, tokens)));
    return application.run();
  }

  private Map<String, Object> httpProperties(boolean multi) {
    return Map.ofEntries(
        Map.entry("spring.config.name", "admin-http-synthetic"),
        Map.entry("spring.main.banner-mode", "off"),
        Map.entry("spring.profiles.active", "daemon"),
        Map.entry("server.port", appPort),
        Map.entry("management.server.port", managementPort),
        Map.entry("management.server.address", "127.0.0.1"),
        Map.entry("management.endpoints.web.exposure.include", "health"),
        Map.entry("scos.mcp.http.enabled", true),
        Map.entry("scos.mcp.http.token", SHARED),
        Map.entry("scos.access.mode", multi ? "multi-user" : "single-user"),
        Map.entry("scos.mcp.http.allowed-hosts", configuredHost()),
        Map.entry("scos.mcp.http.allowed-origins", "https://client.example.com"),
        Map.entry(
            "spring.autoconfigure.exclude",
            DataSourceAutoConfiguration.class.getName()
                + ","
                + FlywayAutoConfiguration.class.getName()));
  }

  private BeanFactoryPostProcessor externalResources(boolean multi, Optional<UserTokens> tokens) {
    return factory -> {
      factory.registerSingleton("adminPort", port);
      tokens.ifPresent(value -> factory.registerSingleton("mcpHttpUserTokens", value));
      authenticationDatabase.ifPresent(
          database -> {
            factory.registerSingleton("dataSource", database.source());
            factory.registerSingleton("dataSourceProperties", database.properties());
          });
      factory.registerSingleton("clock", CLOCK);
      factory.registerSingleton(
          "accessProperties",
          new AccessProperties(
              multi ? AccessProperties.Mode.MULTI_USER : AccessProperties.Mode.SINGLE_USER,
              null,
              List.of(),
              Map.of()));
      factory.registerSingleton(
          "mcpHttpEndpoint",
          McpHttpEndpoint.create(
              McpHttpEndpointTest.memoryTools(),
              "/mcp",
              ErrorExposure.RAW,
              McpHttpConfiguration.contextExtractor(TestMcpHttpServer.SINGLE_USER),
              () -> {}));
      factory.registerSingleton("mcpHttpEngine", engine(multi));
      factory.registerSingleton(
          "unitMaintenance",
          new MemoryUnitMaintenance(new MemoryUnitStore(), new MemoryVectorIndex()));
    };
  }

  private ContextEngine engine(boolean multi) {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    var builder =
        ContextEngine.builder()
            .units(units)
            .vectors(vectors)
            .keywords(new MemoryKeywordIndex())
            .embedder(FixedEmbedder.zero(2));
    if (multi) builder.access(new MemoryIdentityStore(), new RecordedSourceFreshness(), DOMAINS);
    else builder.unrestricted(new MemoryUnitMaintenance(units, vectors));
    return builder.build();
  }

  AutoCloseable debugExceptionResolver() {
    return debugLogging(ExceptionHandlerExceptionResolver.class);
  }

  AutoCloseable debugBodyReads() {
    return debugLogging(RequestResponseBodyMethodProcessor.class);
  }

  private AutoCloseable debugLogging(Class<?> owner) {
    var logger = (Logger) LoggerFactory.getLogger(owner);
    var previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    return () -> logger.setLevel(previous);
  }

  private MockMvc mockMvc() {
    var builder = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context);
    context.getBeansOfType(FilterRegistrationBean.class).values().stream()
        .sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
        .forEach(
            bean ->
                builder.addFilter(
                    bean.getFilter(), (String[]) bean.getUrlPatterns().toArray(String[]::new)));
    return builder.build();
  }

  /** HTTP 컨텍스트를 닫고 고장 인증 요청으로 이 Scenario를 소진한다. 이후에는 close()만 호출한다. */
  Reply consumeForAuthenticationFailure(boolean fatal) throws Exception {
    return consumeForAuthenticationFailure(fatal, "/admin/v1/users");
  }

  /** HTTP 컨텍스트를 닫고 지정 경로로 고장 인증 요청을 보낸다. 이후에는 close()만 호출한다. */
  Reply consumeForAuthenticationFailure(boolean fatal, String uri) throws Exception {
    var tokens = new ObservedUserTokens();
    if (fatal) tokens.failAuthentication(new AssertionError(SENTINEL));
    else tokens.failAuthentication(new IllegalStateException(SENTINEL));
    context.close();
    try (var failingContext = start(false, Optional.of(tokens))) {
      return wireRequest("GET", uri, "", adminToken, Map.of("Host", loopbackHost()));
    }
  }

  DebugLogs debugLogs() {
    return new DebugLogs();
  }

  String foreignHostOnAppPort() {
    return "evil.example.com:" + appPort;
  }

  Reply adminWithContentType(String method, String path, String body, String contentType)
      throws Exception {
    return request(
        method,
        path,
        body,
        adminToken,
        Map.of("Host", loopbackHost(), "Content-Type", contentType));
  }

  Reply adminAccepting(String accept) throws Exception {
    return request(
        "GET", "/admin/v1/users", "", adminToken, Map.of("Host", loopbackHost(), "Accept", accept));
  }

  Reply admin(String method, String path, String body) throws Exception {
    return request(method, path, body, adminToken, multi ? configuredHost() : loopbackHost(), null);
  }

  Reply member(String method, String path, String body) throws Exception {
    return request(
        method, path, body, memberToken, multi ? configuredHost() : loopbackHost(), null);
  }

  Reply request(String method, String path, String body, String token, String host, String origin)
      throws Exception {
    var headers = new LinkedHashMap<String, String>();
    headers.put("Host", host);
    headers.put("Content-Type", "application/json");
    if (origin != null) headers.put("Origin", origin);
    return request(method, path, body, token, headers);
  }

  private Reply request(
      String method, String path, String body, String token, Map<String, String> headers)
      throws Exception {
    if (network) return wireRequest(method, path, body, token, headers);
    var request =
        MockMvcRequestBuilders.request(method, URI.create(path))
            .content(body)
            .with(
                req -> {
                  req.setLocalPort(appPort);
                  return req;
                });
    headers.forEach(request::header);
    if (token != null) request.header("Authorization", "Bearer " + token);
    return reply(mvc.perform(request).andReturn().getResponse());
  }

  private Reply wireRequest(
      String method, String path, String body, String token, Map<String, String> headers)
      throws Exception {
    try (var socket = new Socket(InetAddress.getLoopbackAddress(), appPort)) {
      socket.setSoTimeout(15000);
      String message =
          method
              + " "
              + path
              + " HTTP/1.1\r\n"
              + headers.entrySet().stream()
                  .map(header -> header.getKey() + ": " + header.getValue() + "\r\n")
                  .collect(Collectors.joining())
              + "Connection: close\r\nContent-Length: "
              + body.getBytes(StandardCharsets.UTF_8).length
              + "\r\n"
              + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
              + "\r\n"
              + body;
      socket.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
      String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String[] parts = wire.split("\r\n\r\n", 2);
      String content = parts.length > 1 ? parts[1] : "";
      if (parts[0].toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked"))
        content = unchunk(content);
      return new Reply(
          Integer.parseInt(
              parts[0].substring(parts[0].indexOf(' ') + 1, parts[0].indexOf(' ') + 4)),
          content,
          header(parts[0], "WWW-Authenticate"),
          header(parts[0], "Cache-Control"));
    }
  }

  private String unchunk(String content) {
    var body = new StringBuilder();
    int offset = 0;
    while (offset < content.length()) {
      int end = content.indexOf("\r\n", offset);
      int size = Integer.parseInt(content.substring(offset, end), 16);
      if (size == 0) break;
      body.append(content, end + 2, end + 2 + size);
      offset = end + 2 + size + 2;
    }
    return body.toString();
  }

  private String header(String headers, String name) {
    return headers
        .lines()
        .filter(
            line -> line.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":"))
        .map(line -> line.substring(line.indexOf(':') + 1).strip())
        .findFirst()
        .orElse("");
  }

  Reply overHttp(String method, String path, String body, String token) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    var result = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return new Reply(
        result.statusCode(),
        result.body(),
        result.headers().firstValue("WWW-Authenticate").orElse(""),
        result.headers().firstValue("Cache-Control").orElse(""));
  }

  Reply managementRequest() throws Exception {
    var result =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + managementPort + "/admin/v1/users"))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Reply(result.statusCode(), result.body(), "", "");
  }

  Reply host(String host, String origin) throws Exception {
    return request("GET", "/admin/v1/users", "", adminToken, host, origin);
  }

  String configuredHost() {
    return "admin.example.com:" + appPort;
  }

  String loopbackHost() {
    return "127.0.0.1:" + appPort;
  }

  String adminToken() {
    return adminToken;
  }

  String memberToken() {
    return memberToken;
  }

  List<String> userEmails() {
    return port.users(ADMIN, new PageRequest()).items().stream().map(UserAccount::email).toList();
  }

  List<AuditEvent> auditEvents() {
    return port.audit(ADMIN, new AuditQuery()).items();
  }

  void deactivateMember() {
    port.deactivateUser(ADMIN, "member");
  }

  void revokeMemberToken() {
    var id = port.tokens(ADMIN, "member", new PageRequest()).items().getFirst().tokenId();
    port.revokeToken(ADMIN, id);
  }

  void failStorage() {
    fail.set(true);
  }

  void failFatally() {
    fatal.set(true);
  }

  void restoreStorage() {
    fail.set(false);
    fatal.set(false);
  }

  private Reply reply(MockHttpServletResponse r) throws Exception {
    return new Reply(
        r.getStatus(),
        r.getContentAsString(),
        Optional.ofNullable(r.getHeader("WWW-Authenticate")).orElse(""),
        Optional.ofNullable(r.getHeader("Cache-Control")).orElse(""));
  }

  static AdminDiagnostics diagnostics() {
    var scenario = new AdminAccessQueriesScenario();
    scenario.givenObservedReader();
    return new AdminDiagnostics() {
      @Override
      public List<SourceAccessView> sourceAccess() {
        return scenario.sourceAccess();
      }

      @Override
      public Optional<DocumentAccessView> explain(String documentId, Optional<String> userId) {
        return documentId.equals("doc") ? scenario.explain(userId) : Optional.empty();
      }
    };
  }

  private int availablePort() throws Exception {
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  @Override
  public void close() {
    context.close();
    client.close();
  }

  JsonNode userByEmail(String email) throws Exception {
    return admin("GET", "/admin/v1/users", "")
        .json()
        .path("items")
        .valueStream()
        .filter(user -> user.path("email").asString().equals(email))
        .findFirst()
        .orElseThrow();
  }

  /** 루트에서 모든 출력 갈래를 포획하고 요청 처리 로거의 DEBUG 수준을 수명 안에 복원한다. */
  static final class DebugLogs implements AutoCloseable {
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers =
        List.of(
            root,
            (Logger) LoggerFactory.getLogger("io.scos"),
            (Logger) LoggerFactory.getLogger("org.springframework.web"));
    private final List<Level> previous = loggers.stream().map(Logger::getLevel).toList();

    DebugLogs() {
      appender.start();
      root.addAppender(appender);
      loggers.forEach(logger -> logger.setLevel(Level.DEBUG));
    }

    String events() {
      var encoder = new JsonLineEncoder();
      synchronized (appender) {
        return appender.list.stream()
            .map(event -> new String(encoder.encode(event), StandardCharsets.UTF_8))
            .collect(Collectors.joining());
      }
    }

    @Override
    public void close() {
      root.detachAppender(appender);
      appender.stop();
      for (int index = 0; index < loggers.size(); index++)
        loggers.get(index).setLevel(previous.get(index));
    }
  }

  record Database(DataSource source, DataSourceProperties properties) {}

  record Reply(int status, String body, String challenge, String cache) {
    JsonNode json() {
      return JsonMapper.builder().build().readTree(body);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(McpHttpConfiguration.class)
  @ComponentScan(
      basePackageClasses = OpsConfiguration.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.REGEX,
              pattern = "io.scos.server.Admin(HttpConfiguration|Controller|HttpErrors)"))
  static class HttpApplication {}
}
