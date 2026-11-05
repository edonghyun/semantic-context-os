package io.scos.connector.slack.internal;

import io.scos.engine.support.call.ProviderHttp;
import io.scos.engine.support.call.RetryAfter;
import io.scos.spi.connector.CallFailure.Violation;
import io.scos.spi.source.SourceIdentityRecord;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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

  public record MemberPage(List<String> members, Optional<String> nextCursor) {}

  public record UserPage(List<User> users, Optional<String> nextCursor) {}

  public record User(
      String id, Optional<String> email, boolean verified, boolean guest, boolean deleted) {
    SourceIdentityRecord observedAt(Instant at) {
      return new SourceIdentityRecord(id, email, verified, guest, deleted, at);
    }
  }

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
    return joinedChannels(token, types, true, cursor, timeout);
  }

  public ChannelPage listAccessChannels(String token, Optional<String> cursor, Duration timeout) {
    return joinedChannels(
        token, List.of("public_channel", "private_channel", "mpim", "im"), false, cursor, timeout);
  }

  private ChannelPage joinedChannels(
      String token,
      List<String> types,
      boolean excludeArchived,
      Optional<String> cursor,
      Duration timeout) {
    UriComponentsBuilder b =
        UriComponentsBuilder.fromUriString(baseUrl + "/api/users.conversations")
            .queryParam("types", String.join(",", types))
            .queryParam("exclude_archived", String.valueOf(excludeArchived))
            .queryParam("limit", "200");
    cursor.ifPresent(c -> b.queryParam("cursor", c));
    // 본문 목록의 기존 오류 분류는 보존하고, 접근 목록은 HTTP 거부 자체를 우선한다.
    JsonNode body =
        excludeArchived
            ? get(b.build().toUri(), token, timeout)
            : accessGet(b.build().toUri(), token, timeout);
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

  public String teamId(String token, Duration timeout) {
    return text(accessGet(URI.create(baseUrl + "/api/auth.test"), token, timeout), "team_id");
  }

  public boolean isPublicChannel(String token, String channelId, Duration timeout) {
    JsonNode channel =
        accessGet(endpoint("conversations.info", Map.of("channel", channelId)), token, timeout)
            .path("channel");
    if (!channelId.equals(text(channel, "id")))
      throw SlackApiException.invalidResponse("channel_id_mismatch");
    if (optionalFlag(channel, "is_im") || optionalFlag(channel, "is_mpim")) return false;
    return !flag(channel, "is_private") && !flag(channel, "is_im") && !flag(channel, "is_mpim");
  }

  public MemberPage members(
      String token, String channelId, Optional<String> cursor, Duration timeout) {
    JsonNode body =
        accessGet(
            pagedEndpoint("conversations.members", Map.of("channel", channelId), cursor),
            token,
            timeout);
    JsonNode members = array(body, "members");
    List<String> ids = new ArrayList<>();
    for (JsonNode member : members) {
      if (!member.isString() || !member.asString().matches("[UW][A-Z0-9]+"))
        throw SlackApiException.invalidResponse("member_id_shape");
      ids.add(member.asString());
    }
    return new MemberPage(List.copyOf(ids), nextCursor(body));
  }

  public UserPage users(String token, String teamId, Optional<String> cursor, Duration timeout) {
    JsonNode body = accessGet(pagedEndpoint("users.list", Map.of(), cursor), token, timeout);
    List<User> users = new ArrayList<>();
    for (JsonNode member : array(body, "members")) users.add(user(member, teamId));
    return new UserPage(List.copyOf(users), nextCursor(body));
  }

  private static User user(JsonNode member, String teamId) {
    String id = text(member, "id");
    if (!id.matches("[UW][A-Z0-9]+")) throw SlackApiException.invalidResponse("user_id_shape");
    boolean deleted = flag(member, "deleted");
    boolean restricted =
        deleted ? optionalFlag(member, "is_restricted") : flag(member, "is_restricted");
    boolean ultraRestricted =
        deleted ? optionalFlag(member, "is_ultra_restricted") : flag(member, "is_ultra_restricted");
    boolean stranger = optionalFlag(member, "is_stranger");
    boolean bot = deleted ? optionalFlag(member, "is_bot") : flag(member, "is_bot");
    boolean otherTeam = !text(member, "team_id").equals(teamId);
    JsonNode profile = member.path("profile");
    if (!profile.isObject()) throw SlackApiException.invalidResponse("profile_shape");
    JsonNode email = profile.get("email");
    if (email != null && !email.isString()) throw SlackApiException.invalidResponse("email_shape");
    Optional<String> address =
        Optional.ofNullable(email).map(JsonNode::asString).filter(value -> !value.isBlank());
    return new User(
        id,
        address,
        optionalFlag(member, "is_email_confirmed"),
        restricted || ultraRestricted || stranger || bot || otherTeam,
        deleted);
  }

  private URI pagedEndpoint(String method, Map<String, String> params, Optional<String> cursor) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUri(endpoint(method, params)).queryParam("limit", "200");
    cursor.ifPresent(value -> builder.queryParam("cursor", value));
    return builder.build().toUri();
  }

  private URI endpoint(String method, Map<String, String> params) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/api/" + method);
    params.forEach(builder::queryParam);
    return builder.build().toUri();
  }

  private static JsonNode array(JsonNode body, String field) {
    JsonNode value = body.path(field);
    if (!value.isArray()) throw SlackApiException.invalidResponse(field + "_not_array");
    return value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank())
      throw SlackApiException.invalidResponse(field + "_shape");
    return value.asString();
  }

  private static boolean flag(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isBoolean()) throw SlackApiException.invalidResponse(field + "_shape");
    return value.asBoolean();
  }

  private static boolean optionalFlag(JsonNode node, String field) {
    return node.has(field) && flag(node, field);
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
    return parseResponse(response);
  }

  private JsonNode accessGet(URI uri, String token, Duration timeout) {
    ProviderHttp.Response response =
        http.get(uri, Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token), timeout);
    if (response.status() == 403 || response.status() == 404)
      throw new SlackTransportException(response.status(), "access denied");
    return parseResponse(response);
  }

  private JsonNode parseResponse(ProviderHttp.Response response) {
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
