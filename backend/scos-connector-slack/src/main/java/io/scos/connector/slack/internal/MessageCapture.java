package io.scos.connector.slack.internal;

import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.source.SourceInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 한 채널(또는 그 채널의 스레드 하나)의 메시지 페이지를 원본 변경·거부·스레드 루트로 가른다. historySince 밖의 메시지는 본문을 잡기 전에 버린다 — 경계 밖
 * 본문은 원본 저장소에도 격리에도 들어가지 않는다.
 */
final class MessageCapture {
  private static final String MISSING_TS = "Slack message must have a valid ts";
  private static final String STRAY_REPLY =
      "Slack thread reply must reference the requested thread";

  private final ObjectMapper json;
  private final Clock clock;
  private final ContentCapture contents;
  private final String channelId;
  private final SourceInstanceId source;
  private final Optional<SlackTs> thread;
  private final Optional<SlackTs> historySince;

  MessageCapture(
      ObjectMapper json,
      Clock clock,
      ContentCapture contents,
      SourceInstanceId source,
      String channelId,
      Optional<SlackTs> thread,
      Optional<SlackTs> historySince) {
    this.json = json;
    this.clock = clock;
    this.contents = contents;
    this.source = source;
    this.channelId = channelId;
    this.thread = thread;
    this.historySince = historySince;
  }

  CapturedMessages capture(List<String> messages) {
    Tally tally = new Tally();
    for (int index = 0; index < messages.size(); index++) take(tally, index, messages.get(index));
    return tally.result();
  }

  private void take(Tally tally, int index, String raw) {
    SlackMessage message = SlackMessage.parse(json, raw);
    Instant now = clock.instant();
    if (historySince.isPresent()) {
      if (message.ts().isEmpty()) {
        tally.reject(rejection(MISSING_TS, Optional.empty(), index, now, Optional.empty()));
        return;
      }
      if (message.ts().get().isBefore(historySince.get())) return;
    }
    RawContent content = contents.json("application/json", raw);
    if (message.ts().isEmpty()) {
      tally.reject(rejection(MISSING_TS, thread, index, now, Optional.of(content)));
      return;
    }
    if (thread.isPresent() && !message.belongsTo(thread.get())) {
      tally.reject(rejection(STRAY_REPLY, thread, index, now, Optional.of(content)));
      return;
    }
    tally.accept(
        message.toRawItem(json, source, channelId, now, content),
        message.ts().get(),
        thread.isEmpty() && message.startsThread());
  }

  private RejectedItem rejection(
      String reason,
      Optional<SlackTs> locatorThread,
      int index,
      Instant now,
      Optional<RawContent> quarantined) {
    ObjectNode locator = json.createObjectNode();
    locator.put("channelId", channelId);
    locatorThread.ifPresent(t -> locator.put("threadTs", t.text()));
    locator.put("messageIndex", index);
    return new RejectedItem(
        RejectedItem.FailureKind.INVALID_ITEM,
        reason,
        Optional.of(json.writeValueAsString(locator)),
        now,
        quarantined);
  }

  private static final class Tally {
    private final List<RawMutation> mutations = new ArrayList<>();
    private final List<RejectedItem> rejections = new ArrayList<>();
    private final Set<SlackTs> threadRoots = new LinkedHashSet<>();
    private Optional<SlackTs> latestSeen = Optional.empty();

    void reject(RejectedItem rejection) {
      rejections.add(rejection);
    }

    void accept(RawItem item, SlackTs ts, boolean threadRoot) {
      mutations.add(new RawMutation.Upsert(item));
      latestSeen = Optional.of(latestSeen.map(seen -> seen.laterOf(ts)).orElse(ts));
      if (threadRoot) threadRoots.add(ts);
    }

    CapturedMessages result() {
      return new CapturedMessages(
          List.copyOf(mutations), List.copyOf(rejections), List.copyOf(threadRoots), latestSeen);
    }
  }
}
