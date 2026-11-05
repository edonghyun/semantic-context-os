package io.scos.connector.jira.internal;

import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** ACL 전용 PageBean 전체성 검증. 본문 수집의 관대한 목록 종료 규칙과 캐시를 공유하지 않는다. */
final class JiraAccessResponses {
  private static final int MAX_PAGES = 1000;
  private static final Pattern NEXT_OFFSET = Pattern.compile("(?:^|&)startAt=([0-9]+)(?=&|$)");
  private final AccessInput input;
  private final JiraConfiguration config;
  private final String token;
  private final JiraApiClient client;
  private final Map<String, JsonNode> responses = new LinkedHashMap<>();

  JiraAccessResponses(
      AccessInput input, JiraConfiguration config, String token, JiraApiClient client) {
    this.input = input;
    this.config = config;
    this.token = token;
    this.client = client;
  }

  JsonNode read(String path) {
    if (input.cancel().isCancelled())
      throw new CallFailureException(
          "jira.access", CallFailure.of(CallFailure.Kind.CANCELLED, "Jira access cancelled"));
    return responses.computeIfAbsent(
        path,
        request ->
            input
                .calls()
                .execute(
                    "jira.access.read",
                    timeout -> client.json(config, token, "/rest/api/3" + request, timeout),
                    JiraErrors::classify,
                    input.cancel()));
  }

  List<JsonNode> list(String path) {
    List<JsonNode> all = new ArrayList<>();
    visit(path, all::add);
    return List.copyOf(all);
  }

  /** 검증된 페이지의 개별 관측은 후속 페이지 실패와 무관하게 보존할 수 있다. */
  void visit(String path, Consumer<JsonNode> observe) {
    int offset = 0;
    int total = -1;
    for (int page = 0; page < MAX_PAGES; page++) {
      JsonNode body = read(path + "&startAt=" + offset + "&maxResults=50");
      int observedTotal = integer(body, "total");
      int start = integer(body, "startAt");
      int max = integer(body, "maxResults");
      JsonNode values = array(body, "values");
      JsonNode last = body.path("isLast");
      if (start != offset
          || (total != -1 && total != observedTotal)
          || max <= 0
          || values.size() > max
          || !last.isBoolean()) throw incomplete("Invalid Jira access page");
      total = observedTotal;
      long next = (long) offset + values.size();
      if (next > total
          || last.asBoolean() != (next == total)
          || (!last.asBoolean() && values.isEmpty()))
        throw incomplete("Conflicting Jira access page end");
      verifyNextPage(body, last.asBoolean(), next);
      values.forEach(observe);
      if (last.asBoolean()) return;
      offset = Math.toIntExact(next);
    }
    throw incomplete("Jira access page limit");
  }

  private void verifyNextPage(JsonNode body, boolean last, long next) {
    if (!body.has("nextPage")) return;
    if (last) throw incomplete("Jira terminal page has nextPage");
    String address = text(body, "nextPage");
    try {
      String query = URI.create(address).getRawQuery();
      if (query == null) throw incomplete("Missing Jira nextPage offset");
      var offset = NEXT_OFFSET.matcher(query);
      if (!offset.find() || !offset.group(1).equals(Long.toString(next)) || offset.find())
        throw incomplete("Conflicting Jira nextPage offset");
    } catch (IllegalArgumentException failure) {
      throw incomplete("Malformed Jira nextPage");
    }
  }

  static JsonNode array(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isArray()) throw incomplete("Missing Jira access array: " + name);
    return value;
  }

  static String text(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isString() || value.asString().isBlank())
      throw incomplete("Missing Jira access field: " + name);
    return value.asString();
  }

  static String id(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (value.isIntegralNumber()) return value.asString();
    return text(body, name);
  }

  static int integer(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
      throw incomplete("Invalid Jira access integer: " + name);
    return value.asInt();
  }

  static CallFailureException incomplete(String reason) {
    return new CallFailureException(
        "jira.access", CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, reason));
  }
}
