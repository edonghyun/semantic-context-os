package io.scos.engine.support.call;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 임베딩·생성 공급자의 JSON POST(ADR-0020 §5): {@link ProviderHttp} + {@link ProviderCallExecutor}. 429는
 * Retry-After를 존중해 재시도, 5xx·전송 오류는 backoff 재시도, 401·403·그 밖의 4xx는 즉시 실패. 응답 본문은 예외 메시지에 싣지 않는다(H6)
 * — 본문 error의 식별자 코드만 꺼낸다({@link #errorCode}). 공급자마다 다른 것은 {@link Dialect}(이름표·인증 헤더·error 코드
 * 필드)뿐이다.
 */
public final class ProviderJsonHttp {
  /** 공급자 말투: 실패 문구의 이름표(예: "Gemini"), 요청마다 보낼 헤더, 본문 error에서 코드를 찾는 필드 순서. */
  public record Dialect(String label, Map<String, String> headers, List<String> errorCodeFields) {
    public Dialect {
      headers = Map.copyOf(headers);
      errorCodeFields = List.copyOf(errorCodeFields);
    }
  }

  /** 재시도가 끝난 실패: 마지막 HTTP 상태(전송 오류·취소면 없음)와 그 응답의 error 코드, 분류. */
  public static final class Failure extends RuntimeException {
    private final Optional<Integer> status;
    private final Optional<String> errorCode;
    private final CallFailure failure;

    Failure(Optional<Integer> status, Optional<String> errorCode, CallFailure failure) {
      super(failure.kind().wire() + status.map(s -> " " + s).orElse(""));
      this.status = status;
      this.errorCode = errorCode;
      this.failure = failure;
    }

    public Optional<Integer> status() {
      return status;
    }

    /** 마지막 비2xx 응답의 error 식별자 코드. */
    public Optional<String> errorCode() {
      return errorCode;
    }

    public CallFailure failure() {
      return failure;
    }

    /** 생성 실패 문구: 상태가 있으면 "{api} {상태}", 없으면 "{api} request failed: {종류}"(본문은 싣지 않는다). */
    public String generationMessage(String api) {
      return status
          .map(s -> api + " " + s)
          .orElseGet(() -> api + " request failed: " + failure.kind().wire());
    }
  }

  /** 비2xx 응답(시도 하나의 결과). */
  private static final class HttpStatusException extends RuntimeException {
    final int status;
    final Optional<Duration> retryAfter;

    HttpStatusException(int status, Optional<Duration> retryAfter) {
      super("HTTP " + status);
      this.status = status;
      this.retryAfter = retryAfter;
    }
  }

  /** 메시지에 실어도 되는 코드 모양 — 자유 문장·긴 값은 본문 원문일 수 있다(H6). */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProviderHttp http;
  private final ProviderCallExecutor executor;
  private final Dialect dialect;

  public ProviderJsonHttp(ProviderCallExecutor executor, Dialect dialect) {
    this.executor = executor;
    this.http = ProviderHttp.of(HttpClient.newHttpClient(), executor);
    this.dialect = dialect;
  }

  /** JSON POST → 2xx 본문. 재시도 소진·비재시도 실패는 {@link Failure}. */
  public String post(String operation, URI endpoint, String json) {
    AtomicReference<Integer> lastStatus = new AtomicReference<>();
    AtomicReference<Optional<String>> lastErrorCode = new AtomicReference<>(Optional.empty());
    try {
      return executor.execute(
          operation,
          timeout -> {
            ProviderHttp.Response response =
                http.postJson(endpoint, dialect.headers(), json, timeout);
            int status = response.status();
            if (status / 100 != 2) {
              lastStatus.set(status);
              lastErrorCode.set(errorCode(response.body()));
              throw new HttpStatusException(status, retryAfter(response.headers()));
            }
            return response.body();
          },
          this::classify,
          // 임베더·생성기 포트는 토큰을 받지 않는다 — 취소는 호출 스레드 인터럽트로 온다(ProviderCallExecutor)
          CancellationToken.none());
    } catch (CallFailureException e) {
      // 취소(데몬 종료 인터럽트)는 마지막 HTTP 상태의 실패가 아니다 — 상태를 싣지 않아 투영이 429·5xx로 분류하지 않는다
      Optional<Integer> status =
          e.failure().kind() == CallFailure.Kind.CANCELLED
              ? Optional.empty()
              : Optional.ofNullable(lastStatus.get());
      // lastErrorCode는 Optional.empty()로 시작하고 errorCode(…)의 Optional만 받는다 — requireNonNull은 실패하지
      // 않는다
      throw new Failure(
          status,
          status.isPresent() ? Objects.requireNonNull(lastErrorCode.get()) : Optional.empty(),
          e.failure());
    }
  }

  /**
   * 본문 error의 코드: JSON 객체 error에서 말투의 필드 순서로 처음 나오는 비어 있지 않은 문자열. 그 값이 식별자({@code
   * [A-Za-z0-9_]{1,64}})일 때만 돌려준다 — 아니면 뒤 필드로 넘어가지 않고 없음(H6).
   */
  Optional<String> errorCode(String body) {
    JsonNode error;
    try {
      error = JSON.readTree(body).path("error");
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (!error.isObject()) return Optional.empty();
    for (String field : dialect.errorCodeFields()) {
      JsonNode value = error.path(field);
      if (value.isString() && !value.asString().isEmpty())
        return IDENTIFIER.matcher(value.asString()).matches()
            ? Optional.of(value.asString())
            : Optional.empty();
    }
    return Optional.empty();
  }

  /** 정수 초만(앞뒤 공백 허용). */
  private static Optional<Duration> retryAfter(HttpHeaders headers) {
    String v = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (v == null) return Optional.empty();
    try {
      return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * 상태 → 분류: 429 rate_limited(Retry-After), 5xx·전송 오류 dependency_unavailable, 401·403, 그 밖 4xx는
   * invalid_provider_response.
   */
  CallFailure classify(Exception error) {
    String label = dialect.label();
    if (error instanceof HttpStatusException h) {
      if (h.status == 429) return CallFailure.rateLimited(label + " rate limited", h.retryAfter);
      if (h.status == 401)
        return CallFailure.of(CallFailure.Kind.AUTHENTICATION_FAILED, label + " 401");
      if (h.status == 403)
        return CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, label + " 403");
      if (h.status / 100 == 5)
        return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, label + " " + h.status);
      return CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, label + " " + h.status);
    }
    if (error instanceof ResourceAccessException)
      return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, label + " request failed");
    return CallFailure.of(
        CallFailure.Kind.DEPENDENCY_UNAVAILABLE, error.getClass().getSimpleName());
  }
}
