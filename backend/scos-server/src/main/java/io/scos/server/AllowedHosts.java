package io.scos.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** 다중 사용자 HTTP의 Host·Origin 허용 경계. */
final class AllowedHosts implements Filter {
  private final List<String> hosts;
  private final List<String> origins;

  AllowedHosts(List<String> hosts, List<String> origins) {
    this.hosts = List.copyOf(hosts);
    this.origins = List.copyOf(origins);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var req = (HttpServletRequest) request;
    String host = req.getHeader("Host");
    String origin = req.getHeader("Origin");
    if (Collections.list(req.getHeaders("Host")).size() == 1
        && Collections.list(req.getHeaders("Origin")).size() <= 1
        && host != null
        && !host.isBlank()
        && hosts.contains(host)
        && (origin == null || origins.contains(origin))) {
      chain.doFilter(request, response);
      return;
    }
    var http = (HttpServletResponse) response;
    http.setStatus(HttpServletResponse.SC_FORBIDDEN);
    http.setContentType("application/json");
    http.getOutputStream().write("{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
  }
}
