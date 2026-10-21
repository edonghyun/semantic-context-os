package io.scos.connector.googledrive.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import io.scos.engine.support.text.Utf8;
import io.scos.spi.connector.CredentialResolver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 자격 증명 하나의 Google 세션(TS GoogleDriveSession). 시도 하나의 교환만 한다 — 재시도·대기는 {@code ProviderCallExecutor}의
 * 몫이고, 토큰 갱신도 그 시도 안에서 한다.
 *
 * <ul>
 *   <li>access token은 메모리에만 둔다. 만료 60초 전부터 refresh token으로 다시 받는다(토큰 끝점 POST 폼). 데이터 요청이 401이면 무효화해
 *       다음 요청이 갱신한다. 갱신 실패: 5xx·429는 그 상태, 그 밖은 401(재인증), 2xx인데 토큰 모양이 아니면 502
 *       invalid_oauth_response.
 *   <li>경로는 서비스의 버전 접두로 시작해야 하고 역슬래시·줄바꿈이 없어야 한다 — 아니면 요청 없이 실패한다(자격 증명이 다른 곳으로 가지 않는다).
 *   <li>2xx가 아니면 {@link GoogleApiException}: 403은 본문(1 MiB까지)의 rate limit 이유만 허용 목록으로 꺼내고,
 *       Retry-After는 0보다 큰 초만. 3xx는 따르지 않고 응답 없는 전송 실패로 본다(TS fetch redirect:"error").
 *   <li>JSON 응답은 UTF-8(BOM은 뗀다)로 읽은 객체여야 한다 — 아니면 502 invalid_json, 본문 없는 상태(204·205)는 502
 *       missing_body. 본문이 50 MiB를 넘으면 {@link GoogleContentTooLargeException}.
 * </ul>
 *
 * 오류에는 상태와 허용 목록의 이유만 싣는다 — 자격 증명·access token·응답 본문은 싣지 않는다.
 */
public final class GoogleDriveSession {
  static final long MAX_JSON_BYTES = 50L * 1024 * 1024;
  static final int MAX_SMALL_BODY_BYTES = 1024 * 1024;
  static final String TOKEN_PATH = "/token";
  static final Set<String> RATE_LIMIT_REASONS =
      Set.of("rateLimitExceeded", "userRateLimitExceeded", "sharingRateLimitExceeded");

  private static final Pattern UNSAFE_PATH = Pattern.compile("[\\\\\\r\\n]");
  private static final long REFRESH_MARGIN_SECONDS = 60;
  private static final double MILLIS_PER_SECOND = 1000;
  private static final int SUCCESS_MIN = 200;
  private static final int SUCCESS_MAX = 299;
  private static final int REDIRECT_MIN = 300;
  private static final int REDIRECT_MAX = 399;
  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int SERVER_ERRORS = 500;
  private static final int BAD_GATEWAY = 502;
  private static final Set<Integer> NULL_BODY = Set.of(204, 205);
  private static final char BOM = '﻿';

  /** 리다이렉트 응답 — 따르지 않고 상태 없는 전송 실패로 분류된다. */
  static final class RedirectRefused extends RuntimeException {
    RedirectRefused() {
      super("Google redirect refused");
    }
  }

  private final GoogleCredential credential;
  private final ProviderHttp http;
  private final ObjectMapper json;
  private final Clock clock;
  private final GoogleOrigins origins;
  private String accessToken;
  private double expiresAt;

  GoogleDriveSession(
      GoogleCredential credential,
      ProviderHttp http,
      ObjectMapper json,
      Clock clock,
      GoogleOrigins origins) {
    this.credential = credential;
    this.http = http;
    this.json = json;
    this.clock = clock;
    this.origins = origins;
  }

  /** JSON 객체 응답 하나. pathAndQuery는 인코딩을 마친 경로 + 쿼리다. */
  public JsonNode json(GoogleService service, String pathAndQuery, Duration timeout) {
    ProviderHttp.BinaryResponse response = request(service, pathAndQuery, timeout, MAX_JSON_BYTES);
    byte[] body = body(response);
    JsonNode value;
    try {
      value = json.readTree(text(body));
    } catch (JacksonException e) {
      throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INVALID_JSON);
    }
    if (!value.isObject())
      throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INVALID_JSON);
    return value;
  }

  /** 파일 원본(alt=media). maxBytes를 넘으면 {@link GoogleContentTooLargeException}. */
  public byte[] download(String fileId, Duration timeout, long maxBytes) {
    String path =
        "/drive/v3/files/" + GoogleQuery.component(fileId) + "?alt=media&supportsAllDrives=true";
    return body(request(GoogleService.DRIVE, path, timeout, maxBytes));
  }

  private ProviderHttp.BinaryResponse request(
      GoogleService service, String pathAndQuery, Duration timeout, long maxBytes) {
    if (!pathAndQuery.startsWith(service.pathPrefix()) || UNSAFE_PATH.matcher(pathAndQuery).find())
      throw new IllegalArgumentException("Invalid Google API path");
    URI uri = URI.create(origins.of(service) + pathAndQuery);
    String token = token(timeout);
    ProviderHttp.BinaryResponse response;
    try {
      response =
          http.getBytes(
              uri,
              // 헤더로 보낼 수 없는 access token(토큰 끝점이 준 값)은 헤더 지점에서 설정 오류로 끊는다 — 값은 싣지 않는다
              Map.of(HttpHeaders.AUTHORIZATION, CredentialResolver.headerValue("Bearer " + token)),
              timeout,
              maxBytes);
    } catch (ProviderHttp.ResponseTooLargeException e) {
      throw new GoogleContentTooLargeException();
    }
    int status = response.status();
    if (status >= SUCCESS_MIN && status <= SUCCESS_MAX) return response;
    if (status >= REDIRECT_MIN && status <= REDIRECT_MAX) throw new RedirectRefused();
    if (status == UNAUTHORIZED) invalidate();
    Optional<GoogleApiException.Reason> reason =
        status == FORBIDDEN && rateLimited(response.body())
            ? Optional.of(GoogleApiException.Reason.RATE_LIMITED)
            : Optional.empty();
    throw new GoogleApiException(
        status,
        reason,
        RetryAfter.positiveSeconds(response.headers().getFirst(HttpHeaders.RETRY_AFTER)));
  }

  /** 캐시한 access token, 만료 60초 전부터는 새로 받은 것. */
  private synchronized String token(Duration timeout) {
    if (accessToken != null && clock.millis() < expiresAt) return accessToken;
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", credential.clientId());
    form.put("client_secret", credential.clientSecret());
    form.put("refresh_token", credential.refreshToken());
    form.put("grant_type", "refresh_token");
    ProviderHttp.Response response =
        http.postForm(URI.create(origins.oauth() + TOKEN_PATH), Map.of(), form, timeout);
    int status = response.status();
    if (status >= REDIRECT_MIN && status <= REDIRECT_MAX) throw new RedirectRefused();
    if (status < SUCCESS_MIN || status > SUCCESS_MAX)
      throw new GoogleApiException(
          status >= SERVER_ERRORS || status == TOO_MANY_REQUESTS ? status : UNAUTHORIZED,
          Optional.empty(),
          Optional.empty());
    JsonNode body = tokenBody(response.body());
    JsonNode access = body.get("access_token");
    JsonNode expiresIn = body.get("expires_in");
    if (!JsValues.nonEmptyString(access)
        || expiresIn == null
        || !expiresIn.isNumber()
        || expiresIn.asDouble() <= 0)
      throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INVALID_OAUTH_RESPONSE);
    accessToken = access.asString();
    expiresAt =
        clock.millis()
            + Math.max(0, expiresIn.asDouble() - REFRESH_MARGIN_SECONDS) * MILLIS_PER_SECOND;
    return accessToken;
  }

  private synchronized void invalidate() {
    expiresAt = 0;
  }

  private JsonNode tokenBody(String body) {
    try {
      if (body.getBytes(StandardCharsets.UTF_8).length > MAX_SMALL_BODY_BYTES)
        throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INVALID_OAUTH_RESPONSE);
      JsonNode value = json.readTree(stripBom(body));
      if (!value.isObject())
        throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INVALID_OAUTH_RESPONSE);
      return value;
    } catch (JacksonException e) {
      throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.INVALID_OAUTH_RESPONSE);
    }
  }

  /** 403 본문의 errors[] 이유가 rate limit 계열인가(TS: 앞에서부터 보고, 객체가 아닌 항목이나 읽을 수 없는 본문이면 아니다). */
  private boolean rateLimited(byte[] body) {
    if (body.length > MAX_SMALL_BODY_BYTES) return false;
    try {
      JsonNode root = json.readTree(text(body));
      JsonNode error = root.isObject() ? root.get("error") : null;
      if (error == null || !error.isObject()) return false;
      JsonNode errors = error.get("errors");
      if (errors == null || !errors.isArray()) return false;
      for (JsonNode entry : errors) {
        if (!entry.isObject()) return false;
        if (RATE_LIMIT_REASONS.contains(JsValues.string(entry.get("reason")))) return true;
      }
      return false;
    } catch (JacksonException e) {
      return false;
    }
  }

  private static byte[] body(ProviderHttp.BinaryResponse response) {
    if (NULL_BODY.contains(response.status()))
      throw GoogleApiException.of(BAD_GATEWAY, GoogleApiException.Reason.MISSING_BODY);
    return response.body();
  }

  /** TS TextDecoder: WHATWG UTF-8(잘못된 시퀀스의 U+FFFD 수까지 같다), 앞 BOM은 뗀다. */
  static String text(byte[] bytes) {
    return Utf8.decodeLikeTextDecoder(bytes);
  }

  private static String stripBom(String text) {
    return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
  }
}
