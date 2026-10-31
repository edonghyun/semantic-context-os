package io.scos.connector.slack.internal;

import io.scos.connector.slack.internal.ChannelProgress.ThreadCursor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slack 체크포인트(저장 형식 호환): schemaVersion 1·2 읽기, 2 쓰기. `{channelWatermarks, historySince?,
 * activeChannel{channelId, historyCursor?, latestSeen, pendingThreads[{threadTs, cursor?}]}}`. 없는
 * latestSeen은 빈 문자열로 저장한다. 워터마크는 처음 기록한 채널 순서를 지킨다. 형식 위반은 invalid_checkpoint("Invalid Slack source
 * checkpoint").
 */
public record SlackCheckpoint(
    Map<String, SlackTs> watermarks,
    Optional<ChannelProgress> active,
    Optional<SlackTs> historySince) {
  public SlackCheckpoint {
    watermarks = Collections.unmodifiableMap(new LinkedHashMap<>(watermarks));
  }

  /**
   * 이어 걸을 체크포인트. snapshot이거나 저장 당시의 historySince가 지금과 다르면(제거 포함) 페이지네이션·워터마크가 무효라 처음부터 걷는다 — 이때 저장
   * 값의 형식은 따지지 않는다.
   */
  public static SlackCheckpoint resume(
      ObjectMapper json,
      Optional<Checkpoint> stored,
      CollectionMode mode,
      Optional<SlackTs> historySince) {
    // snapshot이어도 저장 값을 먼저 파싱한다 — 깨진 JSON은 모드와 상관없이 같은 때 실패한다
    Optional<JsonNode> value = stored.map(cp -> json.readTree(cp.valueJson()));
    boolean valid =
        mode != CollectionMode.SNAPSHOT
            && value
                .flatMap(SlackCheckpoint::storedHistorySince)
                .equals(historySince.map(SlackTs::text));
    if (!valid || stored.isEmpty())
      return new SlackCheckpoint(Map.of(), Optional.empty(), historySince);
    return read(stored.get().schemaVersion(), value.get(), historySince);
  }

  Optional<SlackTs> watermark(String channelId) {
    return Optional.ofNullable(watermarks.get(channelId));
  }

  Optional<String> activeChannelId() {
    return active.map(ChannelProgress::channelId);
  }

  /** 걷던 채널의 진행. 채널을 끝까지 걸었으면 본 가장 늦은 ts로 워터마크를 옮기고 진행 중 채널을 비운다. */
  SlackCheckpoint after(ChannelProgress progress) {
    if (!progress.finished())
      return new SlackCheckpoint(watermarks, Optional.of(progress), historySince);
    Map<String, SlackTs> moved = new LinkedHashMap<>(watermarks);
    progress.latestSeen().ifPresent(ts -> moved.put(progress.channelId(), ts));
    return new SlackCheckpoint(moved, Optional.empty(), historySince);
  }

  Checkpoint toCheckpoint(ObjectMapper json) {
    ObjectNode root = json.createObjectNode();
    ObjectNode wm = root.putObject("channelWatermarks");
    watermarks.forEach((channelId, ts) -> wm.put(channelId, ts.text()));
    historySince.ifPresent(s -> root.put("historySince", s.text()));
    active.ifPresent(a -> writeActive(root.putObject("activeChannel"), a));
    return new Checkpoint(2, json.writeValueAsString(root));
  }

  private static void writeActive(ObjectNode node, ChannelProgress a) {
    node.put("channelId", a.channelId());
    a.historyCursor().ifPresent(c -> node.put("historyCursor", c));
    node.put("latestSeen", a.latestSeen().map(SlackTs::text).orElse(""));
    ArrayNode threads = node.putArray("pendingThreads");
    for (ThreadCursor t : a.pendingThreads()) {
      ObjectNode tn = threads.addObject();
      tn.put("threadTs", t.threadTs().text());
      t.cursor().ifPresent(c -> tn.put("cursor", c));
    }
  }

  private static SlackCheckpoint read(
      int schemaVersion, JsonNode value, Optional<SlackTs> historySince) {
    if ((schemaVersion != 1 && schemaVersion != 2) || !value.isObject()) throw invalid();
    Map<String, SlackTs> watermarks = readWatermarks(value.get("channelWatermarks"));
    JsonNode active = value.get("activeChannel");
    if (active == null) return new SlackCheckpoint(watermarks, Optional.empty(), historySince);
    ChannelProgress progress = schemaVersion == 1 ? readV1(active) : readV2(active);
    return new SlackCheckpoint(watermarks, Optional.of(progress), historySince);
  }

  private static Map<String, SlackTs> readWatermarks(JsonNode raw) {
    if (raw == null || !raw.isObject()) throw invalid();
    Map<String, SlackTs> watermarks = new LinkedHashMap<>();
    for (var e : raw.properties()) {
      if (!e.getValue().isString()) throw invalid();
      watermarks.put(e.getKey(), timestamp(e.getValue().asString()));
    }
    return watermarks;
  }

  /** v1: 스레드 없이 history 커서(`cursor`)만 있었다. */
  private static ChannelProgress readV1(JsonNode active) {
    if (!active.isObject()
        || !nonEmptyString(active.get("channelId"))
        || !nonEmptyString(active.get("cursor"))
        || !validLatest(active.get("latestSeen"))) throw invalid();
    return new ChannelProgress(
        active.get("channelId").asString(),
        Optional.of(active.get("cursor").asString()),
        SlackTs.parse(active.get("latestSeen").asString()),
        List.of());
  }

  /** v2: history 커서가 없으면 남은 스레드가 있어야 한다. */
  private static ChannelProgress readV2(JsonNode active) {
    if (!active.isObject()
        || !nonEmptyString(active.get("channelId"))
        || (active.has("historyCursor") && !nonEmptyString(active.get("historyCursor")))
        || !validLatest(active.get("latestSeen"))
        || active.get("pendingThreads") == null
        || !active.get("pendingThreads").isArray()) throw invalid();
    List<ThreadCursor> pending = readThreads(active.get("pendingThreads"));
    if (!active.has("historyCursor") && pending.isEmpty()) throw invalid();
    return new ChannelProgress(
        active.get("channelId").asString(),
        active.has("historyCursor")
            ? Optional.of(active.get("historyCursor").asString())
            : Optional.empty(),
        SlackTs.parse(active.get("latestSeen").asString()),
        pending);
  }

  /** 스레드마다 한 번만. */
  private static List<ThreadCursor> readThreads(JsonNode threads) {
    List<ThreadCursor> pending = new ArrayList<>();
    for (JsonNode t : threads) {
      if (!t.isObject()
          || !nonEmptyString(t.get("threadTs"))
          || (t.has("cursor") && !nonEmptyString(t.get("cursor")))) throw invalid();
      pending.add(
          new ThreadCursor(
              timestamp(t.get("threadTs").asString()),
              t.has("cursor") ? Optional.of(t.get("cursor").asString()) : Optional.empty()));
    }
    if (new HashSet<>(pending.stream().map(ThreadCursor::threadTs).toList()).size()
        != pending.size()) throw invalid();
    return pending;
  }

  private static Optional<String> storedHistorySince(JsonNode value) {
    JsonNode since = value.isObject() ? value.get("historySince") : null;
    return since != null && since.isString() ? Optional.of(since.asString()) : Optional.empty();
  }

  private static SlackTs timestamp(String text) {
    return SlackTs.parse(text).orElseThrow(SlackCheckpoint::invalid);
  }

  private static boolean nonEmptyString(JsonNode n) {
    return n != null && n.isString() && !n.asString().isEmpty();
  }

  private static boolean validLatest(JsonNode n) {
    return n != null
        && n.isString()
        && (n.asString().isEmpty() || SlackTs.parse(n.asString()).isPresent());
  }

  private static CallFailureException invalid() {
    return new CallFailureException(
        "slack.checkpoint",
        CallFailure.of(CallFailure.Kind.INVALID_CHECKPOINT, "Invalid Slack source checkpoint"));
  }
}
