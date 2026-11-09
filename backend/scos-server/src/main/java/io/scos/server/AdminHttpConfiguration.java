package io.scos.server;

import io.scos.boot.AccessProperties;
import io.scos.server.McpHttpConfiguration.McpHttpProperties;
import io.scos.spi.access.UserTokens;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/** 관리 HTTP는 앱 포트에서만 열며 사용자 인증 자원은 MCP와 공유한다. */
@Configuration(proxyBeanMethods = false)
@Profile("daemon")
@ConditionalOnProperty(prefix = "scos.mcp.http", name = "enabled", havingValue = "true")
class AdminHttpConfiguration {
  private static final String PATH = "/admin/v1/*";

  @Bean
  FilterRegistrationBean<AdminHttpErrors> adminHttpBoundary(AdminHttpErrors errors) {
    return registration(errors, "scosAdminBoundary", Ordered.HIGHEST_PRECEDENCE);
  }

  @Bean
  FilterRegistrationBean<Filter> adminHttpAllowedHosts(
      AccessProperties access, McpHttpProperties http) {
    Filter policy =
        (request, response, chain) -> {
          if (access.mode() == AccessProperties.Mode.MULTI_USER) {
            new AllowedHosts(http.allowedHosts(), http.allowedOrigins())
                .doFilter(request, response, chain);
          } else {
            int port = ((HttpServletRequest) request).getLocalPort();
            List<String> hosts = List.of("localhost:" + port, "127.0.0.1:" + port, "[::1]:" + port);
            List<String> origins =
                hosts.stream()
                    .flatMap(host -> List.of("http://" + host, "https://" + host).stream())
                    .toList();
            new AllowedHosts(hosts, origins).doFilter(request, response, chain);
          }
        };
    return registration(policy, "scosAdminHosts", Ordered.HIGHEST_PRECEDENCE + 1);
  }

  @Bean
  FilterRegistrationBean<PrincipalBearerFilter> adminHttpPrincipalFilter(
      @Qualifier("mcpHttpUserTokens") UserTokens tokens, TokenUsageRecorder usage, Clock clock) {
    return registration(
        new PrincipalBearerFilter(tokens, usage, clock),
        "scosAdminAuth",
        Ordered.HIGHEST_PRECEDENCE + 2);
  }

  private <T extends Filter> FilterRegistrationBean<T> registration(
      T filter, String name, int order) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns(PATH);
    registration.setName(name);
    registration.setOrder(order);
    return registration;
  }
}
