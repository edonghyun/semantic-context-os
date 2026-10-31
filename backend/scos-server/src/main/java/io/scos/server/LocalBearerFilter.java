package io.scos.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/** 로컬 Host·Origin만(DNS 리바인딩 방어) + 고정 Bearer 토큰. 로컬 아님 403, 토큰 불일치 401. */
final class LocalBearerFilter implements Filter {
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  private final byte[] expected;

  LocalBearerFilter(String token) {
    this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    if (!isLocal(request.getHeader("Host")) || !originAllowed(request.getHeader("Origin"))) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    String auth = request.getHeader("Authorization");
    if (auth == null || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) {
      response.setHeader("WWW-Authenticate", "Bearer");
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(req, res);
  }

  private static boolean isLocal(String hostHeader) {
    if (hostHeader == null || hostHeader.isBlank()) return false;
    String host = hostHeader.trim().toLowerCase(Locale.ROOT);
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      return end > 0
          && isPortOrNothing(host.substring(end + 1))
          && LOCAL_HOSTS.contains(host.substring(0, end + 1));
    }
    int colon = host.indexOf(':');
    if (colon < 0) return LOCAL_HOSTS.contains(host);
    return isPortOrNothing(host.substring(colon)) && LOCAL_HOSTS.contains(host.substring(0, colon));
  }

  private static boolean isPortOrNothing(String rest) {
    return rest.isEmpty() || rest.matches(":\\d+");
  }

  private static boolean originAllowed(String origin) {
    if (origin == null || origin.isBlank()) return true;
    try {
      URI uri = URI.create(origin.trim());
      String scheme = uri.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
      String host = uri.getHost();
      if (host == null) return false;
      String h = host.toLowerCase(Locale.ROOT);
      return LOCAL_HOSTS.contains(h) || LOCAL_HOSTS.contains("[" + h + "]");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
