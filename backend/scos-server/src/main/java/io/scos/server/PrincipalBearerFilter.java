package io.scos.server;

import io.scos.engine.EventLog;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.store.Principal;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 사용자 토큰을 요청 principal로 바꾸는 서블릿 경계. */
final class PrincipalBearerFilter implements Filter {
  static final String PRINCIPAL_ATTRIBUTE = "io.scos.server.principal";
  private static final int REFERENCE_LENGTH = 12;
  private static final String BEARER_PREFIX = "Bearer ";
  private static final Logger LOG = LoggerFactory.getLogger(PrincipalBearerFilter.class);
  private final UserTokens tokens;
  private final TokenUsageRecorder usage;
  private final Clock clock;

  PrincipalBearerFilter(UserTokens tokens, TokenUsageRecorder usage) {
    this(tokens, usage, Clock.systemUTC());
  }

  PrincipalBearerFilter(UserTokens tokens, TokenUsageRecorder usage, Clock clock) {
    this.tokens = tokens;
    this.usage = usage;
    this.clock = clock;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var http = (HttpServletRequest) request;
    String authorization = http.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      unauthorized((HttpServletResponse) response);
      return;
    }
    Optional<Principal> principal;
    TokenHash hash;
    try {
      hash = TokenHash.of(authorization.substring(BEARER_PREFIX.length()));
      principal = tokens.authenticate(hash);
    } catch (Throwable failure) {
      String ref = UUID.randomUUID().toString().substring(0, REFERENCE_LENGTH);
      EventLog.error(LOG, "http.authentication.failed").detail("ref", ref).cause(failure).log();
      internal((HttpServletResponse) response, ref);
      if (failure instanceof Error fatal) {
        response.flushBuffer();
        throw new MaskedAuthenticationError("internal (ref " + ref + ")", fatal);
      }
      return;
    }
    if (principal.isEmpty()) {
      unauthorized((HttpServletResponse) response);
      return;
    }
    http.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
    usage.record(hash, clock.instant());
    chain.doFilter(request, response);
  }

  private void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
  }

  private void internal(HttpServletResponse response, String ref) throws IOException {
    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write(
            ("{\"error\":\"internal\",\"ref\":\"" + ref + "\"}").getBytes(StandardCharsets.UTF_8));
  }

  /** 컨테이너가 Error 메시지를 사용해도 원인은 로그와 suppressed에만 남긴다. */
  private static final class MaskedAuthenticationError extends Error {
    private MaskedAuthenticationError(String message, Error original) {
      super(message);
      addSuppressed(original);
    }
  }
}
