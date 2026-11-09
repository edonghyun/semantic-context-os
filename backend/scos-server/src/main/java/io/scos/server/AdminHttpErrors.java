package io.scos.server;

import io.scos.engine.EventLog;
import io.scos.engine.admin.AdminPort;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.ObjectMapper;

/** 관리 응답과 로그에서 요청·예외 원문을 배제하는 마지막 HTTP 경계. */
@Component
@Profile("daemon")
@ConditionalOnProperty(prefix = "scos.mcp.http", name = "enabled", havingValue = "true")
final class AdminHttpErrors implements Filter, HandlerExceptionResolver, Ordered {
  private static final String ADMIN_REQUEST = "io.scos.server.adminRequest";
  private static final Logger LOG = LoggerFactory.getLogger(AdminHttpErrors.class);
  private final ObjectMapper mapper;

  AdminHttpErrors(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  /** 프레임워크의 기본 resolver가 예외 메시지를 DEBUG로 기록하기 전에 처리한다. */
  @Override
  public @Nullable ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception failure) {
    if (!owns(request)) return null;
    var result = handle(failure);
    response.setStatus(result.getStatusCode().value());
    response.setContentType("application/json");
    result
        .getHeaders()
        .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    try {
      response
          .getOutputStream()
          .write(mapper.writeValueAsBytes(Objects.requireNonNull(result.getBody())));
    } catch (IOException writeFailure) {
      throw new UncheckedIOException(writeFailure);
    }
    return new ModelAndView();
  }

  static boolean owns(ServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(ADMIN_REQUEST));
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var http = (HttpServletResponse) response;
    request.setAttribute(ADMIN_REQUEST, true);
    http.setHeader("Cache-Control", "no-store");
    try {
      chain.doFilter(request, response);
    } catch (Throwable failure) {
      writeInternal(http, failure);
    }
  }

  ResponseEntity<Map<String, String>> handle(Throwable failure) {
    if (failure instanceof NoResourceFoundException || failure instanceof NoHandlerFoundException)
      return ResponseEntity.status(404).body(Map.of("error", "not_found"));
    if (failure instanceof AdminPort.Failure rejected) return rejected(rejected, failure);
    if (failure instanceof IllegalArgumentException
        || failure instanceof HttpMessageNotReadableException
        || failure instanceof ServletRequestBindingException
        || failure instanceof MethodArgumentTypeMismatchException
        || failure instanceof HttpMediaTypeNotSupportedException
        || failure instanceof HttpMediaTypeNotAcceptableException
        || failure instanceof HttpRequestMethodNotSupportedException)
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    return ResponseEntity.internalServerError().body(internal(failure));
  }

  private ResponseEntity<Map<String, String>> rejected(
      AdminPort.Failure rejected, Throwable failure) {
    int status =
        switch (rejected.code()) {
          case "unauthorized" -> 401;
          case "forbidden" -> 403;
          case "invalid_request" -> 400;
          case "not_found" -> 404;
          case "conflict" -> 409;
          default -> 500;
        };
    if (status == 500) return ResponseEntity.internalServerError().body(internal(failure));
    var response = ResponseEntity.status(status);
    if (status == 401) response.header("WWW-Authenticate", "Bearer");
    return response.body(Map.of("error", rejected.code()));
  }

  static void writeInternal(HttpServletResponse response, Throwable failure) throws IOException {
    Map<String, String> body = internal(failure);
    response.resetBuffer();
    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write(
            ("{\"error\":\"internal\",\"ref\":\"" + body.get("ref") + "\"}")
                .getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, String> internal(Throwable failure) {
    String ref = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    EventLog.error(LOG, "admin.http.failed")
        .detail("ref", ref, "errorType", failure.getClass().getSimpleName(), "reason", "internal")
        .log();
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", "internal");
    body.put("ref", ref);
    return body;
  }
}
