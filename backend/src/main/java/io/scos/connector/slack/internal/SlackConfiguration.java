package io.scos.connector.slack.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack 소스 설정(TS readSlackConfiguration, slack-adapter.ts:161-205): schemaVersion 1,
 * conversationTypes(기본 public·private, 4종, 중복 없음, im/mpim은 channelIds 필수), channelIds(비어 있지 않고 중복
 * 없음), historySince(ISO UTC → Slack ts), historyOverlapSeconds(0 이상 정수), eventSigningSecretRef(비어
 * 있지 않은 문자열 — 있으면 삭제를 공급자 이벤트로 안다).
 */
public record SlackConfiguration(
    Optional<String> historySince,
    List<String> conversationTypes,
    Optional<List<String>> channelIds,
    int historyOverlapSeconds,
    boolean eventSigningSecret) {
  static final List<String> ALLOWED_TYPES =
      List.of("public_channel", "private_channel", "im", "mpim");
  private static final Pattern ISO_UTC =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$");

  public static SlackConfiguration read(ObjectMapper json, SourceConfig config) {
    JsonNode value = json.readTree(config.valueJson());
    if (config.schemaVersion() != 1 || !value.isObject())
      throw invalid("Invalid Slack source configuration");
    Optional<List<String>> channelIds = Optional.empty();
    if (value.has("channelIds")) {
      JsonNode ids = value.get("channelIds");
      if (!ids.isArray() || ids.isEmpty()) throw invalid("Invalid Slack source configuration");
      List<String> list = new ArrayList<>();
      for (JsonNode n : ids) {
        if (!n.isString() || n.asString().isEmpty())
          throw invalid("Invalid Slack source configuration");
        list.add(n.asString());
      }
      if (new HashSet<>(list).size() != list.size())
        throw invalid("Invalid Slack source configuration");
      channelIds = Optional.of(List.copyOf(list));
    }
    if (value.has("eventSigningSecretRef")
        && (!value.get("eventSigningSecretRef").isString()
            || value.get("eventSigningSecretRef").asString().isEmpty()))
      throw invalid("Invalid Slack source configuration");
    List<String> types = new ArrayList<>();
    if (value.has("conversationTypes")) {
      JsonNode t = value.get("conversationTypes");
      if (!t.isArray())
        throw invalid("DM collection requires explicit conversationTypes and channelIds");
      for (JsonNode n : t) types.add(n.isString() ? n.asString() : "");
    } else types.addAll(List.of("public_channel", "private_channel"));
    if (types.isEmpty()
        || !types.stream().allMatch(ALLOWED_TYPES::contains)
        || new HashSet<>(types).size() != types.size()
        || (types.stream().anyMatch(t -> t.equals("im") || t.equals("mpim"))
            && channelIds.isEmpty()))
      throw invalid("DM collection requires explicit conversationTypes and channelIds");
    Optional<String> historySince =
        value.has("historySince")
            ? Optional.of(historySinceTimestamp(value.get("historySince")))
            : Optional.empty();
    int overlap = 0;
    if (value.has("historyOverlapSeconds")) {
      JsonNode o = value.get("historyOverlapSeconds");
      if (!o.isIntegralNumber() || o.asLong() < 0 || o.asLong() > Integer.MAX_VALUE)
        throw invalid("Invalid Slack source configuration");
      overlap = (int) o.asLong();
    }
    return new SlackConfiguration(
        historySince, List.copyOf(types), channelIds, overlap, value.has("eventSigningSecretRef"));
  }

  /** ISO UTC(밀리초 선택) → Slack ts "초.마이크로초". 정규형이 아니면 거부. */
  static String historySinceTimestamp(JsonNode node) {
    if (!node.isString() || !ISO_UTC.matcher(node.asString()).matches())
      throw invalid("historySince must be an ISO UTC timestamp");
    String value = node.asString();
    long millis;
    try {
      millis = Instant.parse(value).toEpochMilli();
    } catch (DateTimeParseException e) {
      throw invalid("Invalid historySince timestamp");
    }
    if (millis < 0) throw invalid("Invalid historySince timestamp");
    String canonical =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(millis));
    if (!value.equals(canonical) && !value.equals(canonical.replace(".000Z", "Z")))
      throw invalid("Invalid historySince timestamp");
    return SlackTimestamps.format(
        java.math.BigInteger.valueOf(millis).multiply(java.math.BigInteger.valueOf(1000)));
  }

  static CallFailureException invalid(String message) {
    return new CallFailureException(
        "slack.configuration", CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, message));
  }
}
