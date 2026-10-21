package io.scos.connector.slack.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import java.util.ArrayList;
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
 * Slack 체크포인트(TS slack-adapter.ts:253-366, F12 저장 호환): schemaVersion 1·2 읽기, 2 쓰기.
 * `{channelWatermarks, historySince?, activeChannel{channelId, historyCursor?, latestSeen,
 * pendingThreads[{threadTs, cursor?}]}}`. 형식 위반은 invalid_checkpoint("Invalid Slack source
 * checkpoint").
 */
public final class SlackCheckpoint {
  public record ThreadCursor(String threadTs, Optional<String> cursor) {}

  /** 진행 중 채널 상태(가변 — 어댑터가 페이지마다 갱신한다). */
  public static final class ActiveChannel {
    final String channelId;
    Optional<String> historyCursor;
    String latestSeen;
    List<ThreadCursor> pendingThreads;

    public ActiveChannel(
        String channelId,
        Optional<String> historyCursor,
        String latestSeen,
        List<ThreadCursor> pendingThreads) {
      this.channelId = channelId;
      this.historyCursor = historyCursor;
      this.latestSeen = latestSeen;
      this.pendingThreads = new ArrayList<>(pendingThreads);
    }

    public String channelId() {
      return channelId;
    }

    public Optional<String> historyCursor() {
      return historyCursor;
    }

    public void setHistoryCursor(Optional<String> historyCursor) {
      this.historyCursor = historyCursor;
    }

    public String latestSeen() {
      return latestSeen;
    }

    public void setLatestSeen(String latestSeen) {
      this.latestSeen = latestSeen;
    }

    /** 남은 스레드 커서(가변 목록 그대로 — 어댑터가 앞에서부터 갱신·제거한다). */
    public List<ThreadCursor> pendingThreads() {
      return pendingThreads;
    }

    public void setPendingThreads(List<ThreadCursor> pendingThreads) {
      this.pendingThreads = pendingThreads;
    }
  }

  public record Value(
      Map<String, String> channelWatermarks, Optional<ActiveChannel> activeChannel) {}

  private SlackCheckpoint() {}

  public static Value read(ObjectMapper json, Optional<Checkpoint> checkpoint) {
    if (checkpoint.isEmpty()) return new Value(new LinkedHashMap<>(), Optional.empty());
    Checkpoint cp = checkpoint.get();
    JsonNode value = json.readTree(cp.valueJson());
    if ((cp.schemaVersion() != 1 && cp.schemaVersion() != 2) || !value.isObject()) throw invalid();
    JsonNode raw = value.get("channelWatermarks");
    if (raw == null || !raw.isObject()) throw invalid();
    Map<String, String> watermarks = new LinkedHashMap<>();
    for (var e : raw.properties()) {
      if (!e.getValue().isString() || !SlackTimestamps.isValid(e.getValue().asString()))
        throw invalid();
      watermarks.put(e.getKey(), e.getValue().asString());
    }
    JsonNode active = value.get("activeChannel");
    if (active == null) return new Value(watermarks, Optional.empty());
    if (cp.schemaVersion() == 1) {
      if (!active.isObject()
          || !nonEmptyString(active.get("channelId"))
          || !nonEmptyString(active.get("cursor"))
          || !validLatest(active.get("latestSeen"))) throw invalid();
      return new Value(
          watermarks,
          Optional.of(
              new ActiveChannel(
                  active.get("channelId").asString(),
                  Optional.of(active.get("cursor").asString()),
                  active.get("latestSeen").asString(),
                  List.of())));
    }
    if (!active.isObject()
        || !nonEmptyString(active.get("channelId"))
        || (active.has("historyCursor") && !nonEmptyString(active.get("historyCursor")))
        || !validLatest(active.get("latestSeen"))
        || active.get("pendingThreads") == null
        || !active.get("pendingThreads").isArray()) throw invalid();
    List<ThreadCursor> pending = new ArrayList<>();
    for (JsonNode t : active.get("pendingThreads")) {
      if (!t.isObject()
          || !nonEmptyString(t.get("threadTs"))
          || !SlackTimestamps.isValid(t.get("threadTs").asString())
          || (t.has("cursor") && !nonEmptyString(t.get("cursor")))) throw invalid();
      pending.add(
          new ThreadCursor(
              t.get("threadTs").asString(),
              t.has("cursor") ? Optional.of(t.get("cursor").asString()) : Optional.empty()));
    }
    if (new HashSet<>(pending.stream().map(ThreadCursor::threadTs).toList()).size()
            != pending.size()
        || (!active.has("historyCursor") && pending.isEmpty())) throw invalid();
    return new Value(
        watermarks,
        Optional.of(
            new ActiveChannel(
                active.get("channelId").asString(),
                active.has("historyCursor")
                    ? Optional.of(active.get("historyCursor").asString())
                    : Optional.empty(),
                active.get("latestSeen").asString(),
                pending)));
  }

  /** 저장된 체크포인트의 historySince(있으면) — 경계가 바뀌면 체크포인트를 버린다. */
  public static Optional<String> storedHistorySince(
      ObjectMapper json, Optional<Checkpoint> checkpoint) {
    if (checkpoint.isEmpty()) return Optional.empty();
    JsonNode value = json.readTree(checkpoint.get().valueJson());
    JsonNode since = value.isObject() ? value.get("historySince") : null;
    return since != null && since.isString() ? Optional.of(since.asString()) : Optional.empty();
  }

  public static Checkpoint write(
      ObjectMapper json,
      Map<String, String> watermarks,
      Optional<ActiveChannel> active,
      Optional<String> historySince) {
    ObjectNode root = json.createObjectNode();
    ObjectNode wm = root.putObject("channelWatermarks");
    for (var e : watermarks.entrySet()) wm.put(e.getKey(), e.getValue());
    historySince.ifPresent(s -> root.put("historySince", s));
    active.ifPresent(
        a -> {
          ObjectNode node = root.putObject("activeChannel");
          node.put("channelId", a.channelId);
          a.historyCursor.ifPresent(c -> node.put("historyCursor", c));
          node.put("latestSeen", a.latestSeen);
          ArrayNode threads = node.putArray("pendingThreads");
          for (ThreadCursor t : a.pendingThreads) {
            ObjectNode tn = threads.addObject();
            tn.put("threadTs", t.threadTs());
            t.cursor().ifPresent(c -> tn.put("cursor", c));
          }
        });
    return new Checkpoint(2, json.writeValueAsString(root));
  }

  private static boolean nonEmptyString(JsonNode n) {
    return n != null && n.isString() && !n.asString().isEmpty();
  }

  private static boolean validLatest(JsonNode n) {
    return n != null
        && n.isString()
        && (n.asString().isEmpty() || SlackTimestamps.isValid(n.asString()));
  }

  public static CallFailureException invalid() {
    return new CallFailureException(
        "slack.checkpoint",
        CallFailure.of(CallFailure.Kind.INVALID_CHECKPOINT, "Invalid Slack source checkpoint"));
  }
}
