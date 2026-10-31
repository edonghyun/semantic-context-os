package io.scos.server;

import java.util.List;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Host·Origin 헤더와 필터 통과 여부를 한 요청으로 관찰한다. */
final class AllowedHostsScenario {
  static final String HOST = "mcp.example.com:8081";
  static final String ORIGIN = "https://client.example.com";
  private final AllowedHosts filter;

  AllowedHostsScenario(List<String> hosts, List<String> origins) {
    filter = new AllowedHosts(hosts, origins);
  }

  Reply request(String host, String origin) throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    if (host != null) request.addHeader("Host", host);
    if (origin != null) request.addHeader("Origin", origin);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return new Reply(response, chain.getRequest() != null);
  }

  Reply duplicateHeader(String header) throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.addHeader("Host", HOST);
    request.addHeader("Origin", ORIGIN);
    request.addHeader(header, "https://unlisted.example.com");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return new Reply(response, chain.getRequest() != null);
  }

  record Reply(MockHttpServletResponse response, boolean reachedChain) {}
}
