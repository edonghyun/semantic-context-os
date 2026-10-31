package io.scos.connector.slack.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import io.scos.spi.connector.CallFailure.Violation;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack Web API: conversations.history·conversations.replies·users.conversations. 교환은 {@link
 * ProviderHttp}(RestClient 재사용, ADR-0022 §4), 시도별 timeout은 F7 min(30s, 남은 예산)(ADR-0018 §3).
 * `ok:false`는 {@link SlackApiException}(error 코드, Retry-After 초), 형식 위반은 invalid_provider_response와
 * 어긋난 검사 이름.
 */
public final class SlackWebApiClient {
  public record MessagePage(List<String> messagesJson, Optional<String> nextCursor) {}

  public record ChannelPage(List<String> channelIds, Optional<String> nextCursor) {}

  /** 유한하고 0 이상인 초(빈 값은 0초). */
  private static final RetryAfter RETRY_AFTER = RetryAfter.nonNegativeSeconds();

  private final String baseUrl;
  private final ProviderHttp http;
  private final ObjectMapper json;

  public SlackWebApiClient(String baseUrl, ProviderHttp http, ObjectMapper json) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = http;
    this.json = json;
  }

  public MessagePage history(
      String token,
      String channelId,
      Optional<String> cursor,
      Optional<String> oldest,
      Optional<Boolean> inclusive,
      Duration timeout) {
    UriComponentsBuilder b =
        UriComponentsBuilder.fromUriString(baseUrl + "/api/conversations.history")
            .queryParam("channel", channelId);
    cursor.ifPresent(c -> b.queryParam("cursor", c));
    oldest.ifPresent(o -> b.queryParam("oldest", o));
    inclusive.ifPresent(i -> b.queryParam("inclusive", String.valueOf(i)));
    return messages(get(b.build().toUri(), token, timeout));
  }

  public MessagePage replies(
      String token,
      String channelId,
      String threadTs,
      Optional<String> oldest,
      Optional<Boolean> inclusive,
      Optional<String> cursor,
      Duration timeout) {
    UriComponentsBuilder b =
        UriComponentsBuilder.fromUriString(baseUrl + "/api/conversations.replies")
            .queryParam("channel", channelId)
            .queryParam("ts", threadTs);
    oldest.ifPresent(o -> b.queryParam("oldest", o));
    inclusive.ifPresent(i -> b.queryParam("inclusive", String.valueOf(i)));
    cursor.ifPresent(c -> b.queryParam("cursor", c));
    MessagePage page = messages(get(b.build().toUri(), token, timeout));
    if (page.messagesJson().isEmpty() && oldest.isEmpty())
      throw SlackApiException.invalidResponse(SlackApiException.THREAD_ROOT_MISSING);
    return page;
  }

  public ChannelPage listJoinedChannels(
      String token, List<String> types, Optional<String> cursor, Duration timeout) {
    UriComponentsBuilder b =
        UriComponentsBuilder.fromUriString(baseUrl + "/api/users.conversations")
            .queryParam("types", String.join(",", types))
            .queryParam("exclude_archived", "true")
            .queryParam("limit", "200");
    cursor.ifPresent(c -> b.queryParam("cursor", c));
    JsonNode body = get(b.build().toUri(), token, timeout);
    JsonNode channels = body.get("channels");
    if (channels == null || !channels.isArray())
      throw SlackApiException.invalidResponse(SlackApiException.CHANNELS_NOT_ARRAY);
    List<String> ids = new ArrayList<>();
    for (JsonNode c : channels) {
      if (!c.isObject() || !c.path("id").isString() || c.get("id").asString().isEmpty())
        throw SlackApiException.invalidResponse(
            Violation.item(SlackApiException.ITEM_SHAPE, ids.size()));
      ids.add(c.get("id").asString());
    }
    return new ChannelPage(ids, nextCursor(body));
  }

  private MessagePage messages(JsonNode body) {
    JsonNode messages = body.get("messages");
    if (messages == null || !messages.isArray())
      throw SlackApiException.invalidResponse(SlackApiException.MESSAGES_NOT_ARRAY);
    List<String> out = new ArrayList<>();
    for (JsonNode m : messages) out.add(json.writeValueAsString(m));
    return new MessagePage(out, nextCursor(body));
  }

  /** 다음 커서(있으면). 이미 요청한 커서로 돌아오는지(A → A·A → B → A)는 걷기 전체를 아는 {@link SlackCollection}이 본다. */
  private static Optional<String> nextCursor(JsonNode body) {
    JsonNode metadata = body.get("response_metadata");
    if (metadata == null) return Optional.empty();
    if (!metadata.isObject())
      throw SlackApiException.invalidResponse(SlackApiException.RESPONSE_METADATA_NOT_OBJECT);
    JsonNode next = metadata.get("next_cursor");
    if (next == null || (next.isString() && next.asString().isEmpty())) return Optional.empty();
    if (!next.isString())
      throw SlackApiException.invalidResponse(SlackApiException.NEXT_CURSOR_NOT_STRING);
    return Optional.of(next.asString());
  }

  private JsonNode get(URI uri, String token, Duration timeout) {
    ProviderHttp.Response response =
        http.get(uri, Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token), timeout);
    String text = response.body();
    if (text.isBlank()) throw new SlackTransportException(response.status(), "empty body");
    JsonNode body;
    try {
      body = json.readTree(text);
    } catch (JacksonException e) {
      // 비-JSON 본문(HTML 오류 페이지 등) — 재시도 가능(dependency_unavailable)
      throw new SlackTransportException(response.status(), "non-JSON body");
    }
    if (body == null || !body.isObject())
      throw SlackApiException.invalidResponse(SlackApiException.BODY_NOT_OBJECT);
    if (!body.path("ok").isBoolean())
      throw SlackApiException.invalidResponse(SlackApiException.OK_NOT_BOOLEAN);
    if (!body.get("ok").asBoolean()) {
      JsonNode error = body.get("error");
      if (error == null || !error.isString())
        throw SlackApiException.invalidResponse(SlackApiException.ERROR_CODE_MISSING);
      Optional<Duration> retryAfter = RETRY_AFTER.read(response.headers().getFirst("Retry-After"));
      throw new SlackApiException(error.asString(), retryAfter);
    }
    return body;
  }
}
