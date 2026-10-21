package io.scos.connector.slack;

import io.scos.connector.slack.internal.SlackApiException;
import io.scos.connector.slack.internal.SlackCheckpoint;
import io.scos.connector.slack.internal.SlackConfiguration;
import io.scos.connector.slack.internal.SlackTimestamps;
import io.scos.connector.slack.internal.SlackWebApiClient;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.ListingField;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slack 폴링 어댑터(TS slack-adapter.ts 1:1, ADR-0018 §2·F12): 참여 채널 발견 → 채널 순서 → history 페이지(워터마크 +
 * overlap) → 스레드 replies → 워터마크 확정. 항목 키 `channelId:ts`, sourceVersion = edited.ts ?? ts, 순서 스킴
 * slack.message-state.v1. 오류 분류는 {@link #classify}.
 */
public final class SlackAdapter implements SourceAdapter {
  public static final String KIND = "slack";
  static final String RESOURCE_TYPE = "slack.message";
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final CredentialResolver credentials;
  private final SlackWebApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public SlackAdapter(
      CredentialResolver credentials, SlackWebApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public void validateConfig(SourceConfig config) {
    SlackConfiguration.read(json, config);
  }

  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    SlackConfiguration c = SlackConfiguration.read(json, config);
    return mode == CollectionMode.SNAPSHOT && c.historySince().isEmpty()
        ? CollectionCoverage.COMPLETE_SNAPSHOT
        : CollectionCoverage.INCREMENTAL;
  }

  /** TS deletionDetection: 이벤트 서명 비밀이 설정되면 삭제를 공급자 이벤트로 안다. */
  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return SlackConfiguration.read(json, config).eventSigningSecret()
        ? DeletionDetection.PROVIDER_EVENTS
        : DeletionDetection.NONE;
  }

  /**
   * TS slackCollectionSelection: {conversationTypes, channelIds?, history: "all" | {since: ISO
   * 밀리초}}.
   */
  @Override
  public List<ListingField> listingFields(SourceConfig config) {
    SlackConfiguration c = SlackConfiguration.read(json, config);
    ObjectNode selection = json.createObjectNode();
    ArrayNode types = selection.putArray("conversationTypes");
    c.conversationTypes().forEach(types::add);
    c.channelIds()
        .ifPresent(
            ids -> {
              ArrayNode channels = selection.putArray("channelIds");
              ids.forEach(channels::add);
            });
    if (c.historySince().isEmpty()) selection.put("history", "all");
    else selection.putObject("history").put("since", isoMillis(c.historySince().get()));
    return List.of(new ListingField("slackSelection", json.writeValueAsString(selection)));
  }

  /** Slack ts → TS `new Date(ts * 1000).toISOString()`. */
  private static String isoMillis(String timestamp) {
    long millis =
        SlackTimestamps.micros(timestamp)
            .orElseThrow()
            .divide(BigInteger.valueOf(1000))
            .longValueExact();
    return ISO_MILLIS.format(Instant.ofEpochMilli(millis));
  }

  /** TS classifySlackError. */
  static CallFailure classify(Exception error) {
    if (!(error instanceof SlackApiException api))
      return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "Slack request failed");
    String message = api.getMessage();
    return switch (api.code()) {
      case "invalid_auth" -> CallFailure.of(CallFailure.Kind.AUTHENTICATION_FAILED, message);
      case "missing_scope" -> CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, message);
      case "invalid_provider_response", "thread_not_found" ->
          CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, message);
      case "ratelimited" -> CallFailure.rateLimited(message, api.retryAfter());
      default -> CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, message);
    };
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    if (input.cancel().isCancelled())
      throw new CallFailureException(
          "slack.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
    SlackConfiguration config = SlackConfiguration.read(json, input.source().config());
    Optional<String> checkpointSince = SlackCheckpoint.storedHistorySince(json, input.checkpoint());
    // 경계가 바뀌면(제거 포함) 페이지네이션·워터마크가 무효다
    Optional<Checkpoint> effective =
        input.mode() == CollectionMode.SNAPSHOT || !checkpointSince.equals(config.historySince())
            ? Optional.empty()
            : input.checkpoint();
    SlackCheckpoint.Value checkpoint = SlackCheckpoint.read(json, effective);
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    List<String> joined = discoverJoinedChannelIds(input, token, config.conversationTypes());
    Set<String> joinedSet = new LinkedHashSet<>(joined);
    if (config.channelIds().isPresent()) {
      List<String> missing =
          config.channelIds().get().stream().filter(id -> !joinedSet.contains(id)).toList();
      if (!missing.isEmpty())
        throw new CallFailureException(
            "slack.configuration",
            CallFailure.of(
                CallFailure.Kind.INVALID_CONFIGURATION,
                "Configured Slack channels are not joined: " + String.join(", ", missing)));
    }
    List<String> channelIdsForRun = config.channelIds().orElse(joined);
    Map<String, String> watermarks = checkpoint.channelWatermarks();
    int activeIndex =
        checkpoint.activeChannel().map(a -> channelIdsForRun.indexOf(a.channelId())).orElse(-1);
    List<String> channelIds =
        activeIndex >= 0
            ? channelIdsForRun.subList(activeIndex, channelIdsForRun.size())
            : channelIdsForRun;

    for (String channelId : channelIds) {
      Optional<SlackCheckpoint.ActiveChannel> active =
          checkpoint.activeChannel().filter(a -> a.channelId().equals(channelId));
      String watermark = watermarks.get(channelId);
      String overlap =
          watermark == null ? "" : overlapOldest(watermark, config.historyOverlapSeconds());
      String oldestValue = SlackTimestamps.later(overlap, config.historySince().orElse(""));
      Optional<String> oldest = oldestValue.isEmpty() ? Optional.empty() : Optional.of(oldestValue);
      SlackCheckpoint.ActiveChannel state =
          active.orElseGet(
              () ->
                  new SlackCheckpoint.ActiveChannel(
                      channelId, Optional.empty(), watermark == null ? "" : watermark, List.of()));

      if (active.isPresent()) {
        collectPendingThreads(input, sink, token, watermarks, state, config.historySince());
        if (state.historyCursor().isEmpty()) continue;
      }
      Optional<String> cursor = state.historyCursor();
      do {
        Optional<String> c = cursor;
        boolean inclusive =
            oldest.isPresent()
                && (config.historyOverlapSeconds() > 0
                    || oldestValue.equals(config.historySince().orElse("")));
        SlackWebApiClient.MessagePage history =
            input
                .calls()
                .execute(
                    "slack.conversations.history",
                    t ->
                        client.history(
                            token,
                            channelId,
                            c,
                            oldest,
                            inclusive ? Optional.of(true) : Optional.empty(),
                            t),
                    SlackAdapter::classify,
                    input.cancel());
        Captured captured =
            captureMessages(
                input, channelId, history.messagesJson(), Optional.empty(), config.historySince());
        state.setLatestSeen(SlackTimestamps.later(state.latestSeen(), captured.latestSeen));
        state.setHistoryCursor(history.nextCursor());
        state.setPendingThreads(
            new ArrayList<>(
                captured.threadRoots.stream()
                    .map(ts -> new SlackCheckpoint.ThreadCursor(ts, Optional.empty()))
                    .toList()));
        boolean channelFinished =
            state.historyCursor().isEmpty() && state.pendingThreads().isEmpty();
        if (channelFinished && !state.latestSeen().isEmpty())
          watermarks.put(channelId, state.latestSeen());
        sink.accept(
            new CollectionPage(
                captured.mutations,
                captured.rejections,
                SlackCheckpoint.write(
                    json,
                    watermarks,
                    channelFinished ? Optional.empty() : Optional.of(state),
                    config.historySince())));
        collectPendingThreads(input, sink, token, watermarks, state, config.historySince());
        cursor = state.historyCursor();
      } while (cursor.isPresent());
    }
  }

  private void collectPendingThreads(
      CollectionInput input,
      PageSink sink,
      String token,
      Map<String, String> watermarks,
      SlackCheckpoint.ActiveChannel state,
      Optional<String> historySince) {
    while (!state.pendingThreads().isEmpty()) {
      SlackCheckpoint.ThreadCursor thread = state.pendingThreads().get(0);
      SlackWebApiClient.MessagePage page =
          input
              .calls()
              .execute(
                  "slack.conversations.replies",
                  t ->
                      client.replies(
                          token,
                          state.channelId(),
                          thread.threadTs(),
                          historySince,
                          historySince.isPresent() ? Optional.of(true) : Optional.empty(),
                          thread.cursor(),
                          t),
                  SlackAdapter::classify,
                  input.cancel());
      Captured captured =
          captureMessages(
              input,
              state.channelId(),
              page.messagesJson(),
              Optional.of(thread.threadTs()),
              historySince);
      if (page.nextCursor().isPresent())
        state
            .pendingThreads()
            .set(0, new SlackCheckpoint.ThreadCursor(thread.threadTs(), page.nextCursor()));
      else state.pendingThreads().remove(0);
      boolean channelFinished = state.historyCursor().isEmpty() && state.pendingThreads().isEmpty();
      if (channelFinished && !state.latestSeen().isEmpty())
        watermarks.put(state.channelId(), state.latestSeen());
      sink.accept(
          new CollectionPage(
              captured.mutations,
              captured.rejections,
              SlackCheckpoint.write(
                  json,
                  watermarks,
                  channelFinished ? Optional.empty() : Optional.of(state),
                  historySince)));
    }
  }

  private List<String> discoverJoinedChannelIds(
      CollectionInput input, String token, List<String> types) {
    Set<String> ids = new TreeSet<>();
    Optional<String> cursor = Optional.empty();
    do {
      Optional<String> c = cursor;
      SlackWebApiClient.ChannelPage page =
          input
              .calls()
              .execute(
                  "slack.users.conversations",
                  t -> client.listJoinedChannels(token, types, c, t),
                  SlackAdapter::classify,
                  input.cancel());
      ids.addAll(page.channelIds());
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return List.copyOf(ids);
  }

  private record Captured(
      List<RawMutation> mutations,
      List<RejectedItem> rejections,
      List<String> threadRoots,
      String latestSeen) {}

  private Captured captureMessages(
      CollectionInput input,
      String channelId,
      List<String> messages,
      Optional<String> expectedThreadTs,
      Optional<String> historySince) {
    List<RawMutation> mutations = new ArrayList<>();
    List<RejectedItem> rejections = new ArrayList<>();
    Set<String> threadRoots = new LinkedHashSet<>();
    String latestSeen = "";
    for (int index = 0; index < messages.size(); index++) {
      String raw = messages.get(index);
      JsonNode message = json.readTree(raw);
      Instant now = clock.instant();
      Optional<String> ts = slackTs(message);
      // 경계 밖 본문은 원본 저장소·격리에도 들어가지 않는다 — capture 전에 거른다
      if (historySince.isPresent()) {
        if (ts.isEmpty()) {
          rejections.add(
              new RejectedItem(
                  RejectedItem.FailureKind.INVALID_ITEM,
                  "Slack message must have a valid ts",
                  Optional.of(locator(channelId, Optional.empty(), index)),
                  now,
                  Optional.empty()));
          continue;
        }
        if (SlackTimestamps.micros(ts.get())
                .orElseThrow()
                .compareTo(SlackTimestamps.micros(historySince.get()).orElseThrow())
            < 0) continue;
      }
      RawContent captured = input.content().json("application/json", raw);
      if (ts.isEmpty()) {
        rejections.add(
            new RejectedItem(
                RejectedItem.FailureKind.INVALID_ITEM,
                "Slack message must have a valid ts",
                Optional.of(locator(channelId, expectedThreadTs, index)),
                now,
                Optional.of(captured)));
        continue;
      }
      if (expectedThreadTs.isPresent()
          && !ts.get().equals(expectedThreadTs.get())
          && !expectedThreadTs.get().equals(stringField(message, "thread_ts").orElse(null))) {
        rejections.add(
            new RejectedItem(
                RejectedItem.FailureKind.INVALID_ITEM,
                "Slack thread reply must reference the requested thread",
                Optional.of(locator(channelId, expectedThreadTs, index)),
                now,
                Optional.of(captured)));
        continue;
      }
      mutations.add(new RawMutation.Upsert(upsert(channelId, message, ts.get(), now, captured)));
      latestSeen = SlackTimestamps.later(latestSeen, ts.get());
      if (expectedThreadTs.isEmpty()) {
        JsonNode replyCount = message.get("reply_count");
        if (replyCount != null && replyCount.isIntegralNumber() && replyCount.asLong() > 0)
          threadRoots.add(ts.get());
      }
    }
    return new Captured(mutations, rejections, List.copyOf(threadRoots), latestSeen);
  }

  /**
   * TS slackMessageUpsert: 키 channelId:ts, sourceVersion = edited.ts ?? ts, metadata{channelId,
   * messageTs, threadTs, authorId, subtype}.
   */
  private RawItem upsert(
      String channelId, JsonNode message, String ts, Instant now, RawContent content) {
    Optional<String> edited =
        Optional.ofNullable(message.get("edited"))
            .filter(JsonNode::isObject)
            .flatMap(e -> stringField(e, "ts"))
            .filter(SlackTimestamps::isValid);
    String sourceVersion = edited.orElse(ts);
    ObjectNode metadata = json.createObjectNode();
    metadata.put("channelId", channelId);
    metadata.put("messageTs", ts);
    metadata.put("threadTs", stringField(message, "thread_ts").orElse(null));
    metadata.put("authorId", stringField(message, "user").orElse(null));
    metadata.put("subtype", stringField(message, "subtype").orElse(null));
    return new RawItem(
        channelId + ":" + ts,
        RESOURCE_TYPE,
        sourceVersion,
        Optional.of(SlackTimestamps.order(sourceVersion)),
        Optional.empty(),
        Optional.empty(),
        now,
        json.writeValueAsString(metadata),
        Optional.of(content));
  }

  private String locator(String channelId, Optional<String> threadTs, int messageIndex) {
    ObjectNode node = json.createObjectNode();
    node.put("channelId", channelId);
    threadTs.ifPresent(t -> node.put("threadTs", t));
    node.put("messageIndex", messageIndex);
    return json.writeValueAsString(node);
  }

  private static Optional<String> slackTs(JsonNode message) {
    if (!message.isObject()) return Optional.empty();
    return stringField(message, "ts").filter(SlackTimestamps::isValid);
  }

  private static Optional<String> stringField(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v != null && v.isString() ? Optional.of(v.asString()) : Optional.empty();
  }

  static String overlapOldest(String watermark, int historyOverlapSeconds) {
    if (historyOverlapSeconds == 0) return watermark;
    BigInteger wm = SlackTimestamps.micros(watermark).orElseThrow(SlackCheckpoint::invalid);
    BigInteger overlap =
        BigInteger.valueOf(historyOverlapSeconds).multiply(BigInteger.valueOf(1_000_000));
    return SlackTimestamps.format(
        wm.compareTo(overlap) > 0 ? wm.subtract(overlap) : BigInteger.ZERO);
  }
}
