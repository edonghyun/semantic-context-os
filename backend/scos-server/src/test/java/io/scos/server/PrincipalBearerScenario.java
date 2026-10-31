package io.scos.server;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.access.TokenHash;
import io.scos.spi.store.Principal;
import io.scos.store.memory.ObservedUserTokens;
import io.scos.support.ManualTaskScheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 메모리 토큰과 서블릿 요청 한 번의 인증 결과를 연결한다. */
final class PrincipalBearerScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
  static final Principal ALICE = new Principal("alice");
  static final String TOKEN = "filter-token-fixture";
  static final TokenHash HASH = TokenHash.of(TOKEN);
  private final ObservedUserTokens tokens = new ObservedUserTokens();
  private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  private final TokenUsageRecorder usage = new TokenUsageRecorder(tokens, scheduler);
  private final PrincipalBearerFilter filter =
      new PrincipalBearerFilter(tokens, usage, Clock.fixed(NOW, ZoneOffset.UTC));

  PrincipalBearerScenario() {
    tokens.seed(AccessDirectory.empty().withUser(new User("alice", "alice@example.com", true)));
    tokens.store(HASH, "alice", "test", NOW);
  }

  void revoke() {
    tokens.revoke(HASH, NOW);
  }

  void deactivate() {
    tokens.seed(AccessDirectory.empty().withUser(new User("alice", "alice@example.com", false)));
  }

  void failStorage() {
    tokens.failAuthentication(new IllegalStateException("storage-detail-fixture"));
  }

  Error failStorageFatally() {
    var failure = new AssertionError("storage-detail-fixture");
    tokens.failAuthentication(failure);
    return failure;
  }

  Reply validRequest() throws Exception {
    return request("Bearer " + TOKEN);
  }

  FailedReply fatalRequest() throws Exception {
    var response = new MockHttpServletResponse();
    Optional<Error> failure = Optional.empty();
    try {
      request("Bearer " + TOKEN, response);
    } catch (Error error) {
      failure = Optional.of(error);
    }
    return new FailedReply(
        response.isCommitted(), response.getStatus(), response.getContentAsString(), failure);
  }

  Reply request(String authorization) throws Exception {
    return request(authorization, new MockHttpServletResponse());
  }

  private Reply request(String authorization, MockHttpServletResponse response) throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    if (authorization != null) request.addHeader("Authorization", authorization);
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return new Reply(
        response,
        Optional.ofNullable(chain.getRequest())
            .map(r -> r.getAttribute("io.scos.server.principal")),
        chain.getRequest() != null);
  }

  Optional<Instant> persistedUsage() {
    scheduler.tick();
    return tokens.lastUsedAt(HASH);
  }

  @Override
  public void close() {
    usage.close();
  }

  record FailedReply(boolean committed, int status, String body, Optional<Error> failure) {}

  record Reply(MockHttpServletResponse response, Optional<Object> principal, boolean reachedChain) {
    List<Object> wire() {
      return List.of(
          response.getStatus(),
          response.getHeaderNames().stream()
              .map(name -> List.of(name, response.getHeaders(name)))
              .toList(),
          response.getContentAsByteArray());
    }
  }
}
