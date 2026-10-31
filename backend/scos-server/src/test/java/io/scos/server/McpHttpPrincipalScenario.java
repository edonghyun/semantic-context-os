package io.scos.server;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import io.scos.engine.ContextEngine;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.Link;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.access.TokenHash;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryIdentitySeed;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryUserTokens;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestExecutors;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 인증 필터·SDK 서블릿·제한 엔진을 연결해 두 사용자의 문서 경계를 본다. */
final class McpHttpPrincipalScenario implements AutoCloseable {
  static final String ALICE_TOKEN = "alice-http-token-fixture";
  static final String BOB_TOKEN = "bob-http-token-fixture";
  private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final int DIMENSIONS = 2;
  private static final AccessProperties MULTI_USER =
      new AccessProperties(Mode.MULTI_USER, null, null, null);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final MemoryAclStore acl = new MemoryAclStore();
  private final MemoryUnitStore units = new MemoryUnitStore(acl);
  private final MemoryVectorIndex vectors = new MemoryVectorIndex(acl);
  private final MemoryKeywordIndex keywords = new MemoryKeywordIndex(acl);
  private final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors);
  private final MemoryIdentityStore identities = new MemoryIdentityStore();
  private final MemoryUserTokens tokens = new MemoryUserTokens(identities);
  private final TokenUsageRecorder usage =
      new TokenUsageRecorder(tokens, new ManualTaskScheduler());
  private final PrincipalBearerFilter filter =
      new PrincipalBearerFilter(tokens, usage, Clock.fixed(NOW, ZoneOffset.UTC));
  private final McpHttpEndpoint endpoint;

  McpHttpPrincipalScenario() {
    var directory =
        directoryOf("alice")
            .withUser(user("bob"))
            .withIdentity(new Identity(SOURCE, "bob", false, false))
            .withLink(new Link(SOURCE, "bob", "bob", "manual"));
    new MemoryIdentitySeed(identities).seed(directory);
    tokens.store(TokenHash.of(ALICE_TOKEN), "alice", "alice", NOW);
    tokens.store(TokenHash.of(BOB_TOKEN), "bob", "bob", NOW);
    load("alice");
    load("bob");
    var engine =
        ContextEngine.builder()
            .units(units)
            .keywords(keywords)
            .vectors(vectors)
            .access(identities, new RecordedSourceFreshness(), new DomainPolicy(Set.of(), Map.of()))
            .embedder(FixedEmbedder.zero(DIMENSIONS))
            .lexicalExecutor(TestExecutors.CALLER_THREAD)
            .build();
    endpoint =
        McpHttpEndpoint.create(
            new McpTools(engine, JSON, QueryLog.NONE),
            "/mcp",
            ErrorExposure.MASKED,
            McpHttpConfiguration.contextExtractor(MULTI_USER),
            () -> {});
  }

  private AccessDirectory directoryOf(String id) {
    return AccessDirectory.empty()
        .withUser(user(id))
        .withIdentity(new Identity(SOURCE, id, false, false))
        .withLink(new Link(SOURCE, id, id, "manual"));
  }

  private User user(String id) {
    return new User(id, id + "@example.com", true);
  }

  private void load(String id) {
    var document =
        unit(id + "-guide").inDocument(id + "-guide").titled("정산 가이드").content("정산 " + id).build();
    maintenance.upsert(SpaceId.LEGACY, List.of(document));
    keywords.upsert(
        SpaceId.LEGACY,
        List.of(
            new IndexedText(
                document.id(),
                document.documentId(),
                document.title(),
                document.content(),
                document.partition(),
                false)));
    acl.put(
        SpaceId.LEGACY,
        List.of(
            new DocumentAcl(
                document.documentId(),
                DocumentAcl.Status.OK,
                Set.of(new AccessKey(AccessKey.Kind.USER, SOURCE, id)),
                NOW)));
  }

  List<List<String>> documentsForBothTokens() throws Exception {
    return List.of(documents(callWithToken(ALICE_TOKEN)), documents(callWithToken(BOB_TOKEN)));
  }

  private List<String> documents(JsonNode wire) {
    if (wire.path("result").path("isError").asBoolean()) return List.of();
    var payload = JSON.readTree(wire.path("result").path("content").get(0).path("text").asString());
    var result = new ArrayList<String>();
    payload.path("hits").forEach(hit -> result.add(hit.path("documentId").asString()));
    return List.copyOf(result);
  }

  private JsonNode callWithToken(String token) throws Exception {
    var request = request();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, endpoint.servlet()::service);
    return JSON.readTree(response.getContentAsString());
  }

  JsonNode callWithoutFilter() throws Exception {
    var response = new MockHttpServletResponse();
    endpoint.servlet().service(request(), response);
    return JSON.readTree(response.getContentAsString());
  }

  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.addHeader("Accept", "application/json, text/event-stream");
    request.setContentType("application/json");
    request.setContent(
        """
        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_context","arguments":{"query":"정산"}}}
        """
            .getBytes(StandardCharsets.UTF_8));
    return request;
  }

  @Override
  public void close() throws Exception {
    try {
      endpoint.close();
    } finally {
      usage.close();
    }
  }
}
