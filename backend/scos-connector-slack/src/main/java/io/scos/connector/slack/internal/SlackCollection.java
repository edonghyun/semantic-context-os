package io.scos.connector.slack.internal;

import io.scos.connector.slack.internal.ChannelProgress.ThreadCursor;
import io.scos.engine.support.call.CursorTrail;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.PageSink;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;

/**
 * run 하나가 소유하는 Slack 수집: 걸을 채널을 고르고, 채널마다 시작 지점부터 history 페이지와 그 페이지가 연 스레드의 replies를 걸으며 페이지마다 다음
 * 체크포인트를 낸다. 채널을 끝까지 걸으면 본 가장 늦은 ts가 그 채널의 워터마크가 된다.
 */
public final class SlackCollection {
  private final CollectionInput input;
  private final SlackConfiguration config;
  private final SlackCheckpoint resumed;
  private final String token;
  private final SlackWebApiClient client;
  private final ObjectMapper json;
  private final Clock clock;
  private SlackCheckpoint saved;

  public SlackCollection(
      CollectionInput input,
      SlackConfiguration config,
      SlackCheckpoint resumed,
      String token,
      SlackWebApiClient client,
      ObjectMapper json,
      Clock clock) {
    this.input = input;
    this.config = config;
    this.resumed = resumed;
    this.token = token;
    this.client = client;
    this.json = json;
    this.clock = clock;
    this.saved = resumed;
  }

  public void collect(PageSink sink) {
    ChannelSelection selection = ChannelSelection.of(config, joinedChannelIds());
    for (String channelId : selection.resumingAt(resumed.activeChannelId()))
      walkChannel(channelId, sink);
  }

  /** 진행 중이던 채널이면 남은 스레드부터 마치고, 남은 history가 있으면 이어 걷는다. */
  private void walkChannel(String channelId, PageSink sink) {
    Optional<ChannelProgress> active =
        resumed.active().filter(a -> a.channelId().equals(channelId));
    Optional<SlackTs> watermark = saved.watermark(channelId);
    ChannelStart start = ChannelStart.of(watermark, config);
    ChannelProgress progress =
        active.orElseGet(() -> ChannelProgress.starting(channelId, watermark));
    if (active.isPresent()) {
      progress = walkThreads(progress, sink);
      if (progress.historyCursor().isEmpty()) return;
    }
    CursorTrail history = new CursorTrail();
    do {
      progress = walkThreads(walkHistoryPage(progress, start, history, sink), sink);
    } while (progress.historyCursor().isPresent());
  }

  private ChannelProgress walkHistoryPage(
      ChannelProgress progress, ChannelStart start, CursorTrail trail, PageSink sink) {
    trail.request(progress.historyCursor());
    SlackWebApiClient.MessagePage history =
        call(
            "slack.conversations.history",
            t ->
                advancing(
                    trail,
                    "history:" + progress.channelId(),
                    client.history(
                        token,
                        progress.channelId(),
                        progress.historyCursor(),
                        start.oldestParam(),
                        start.inclusiveParam(),
                        t)));
    CapturedMessages captured = capture(progress.channelId(), Optional.empty(), history);
    return emit(progress.afterHistoryPage(history.nextCursor(), captured), captured, sink);
  }

  private ChannelProgress walkThreads(ChannelProgress progress, PageSink sink) {
    Optional<SlackTs> since = config.historySince();
    Map<SlackTs, CursorTrail> trails = new HashMap<>();
    for (Optional<ThreadCursor> next = progress.nextThread();
        next.isPresent();
        next = progress.nextThread()) {
      ThreadCursor thread = next.get();
      String channelId = progress.channelId();
      CursorTrail trail = trails.computeIfAbsent(thread.threadTs(), ts -> new CursorTrail());
      trail.request(thread.cursor());
      SlackWebApiClient.MessagePage page =
          call(
              "slack.conversations.replies",
              t ->
                  advancing(
                      trail,
                      "replies:" + channelId + ":" + thread.threadTs().text(),
                      client.replies(
                          token,
                          channelId,
                          thread.threadTs().text(),
                          since.map(SlackTs::text),
                          since.isPresent() ? Optional.of(true) : Optional.empty(),
                          thread.cursor(),
                          t)));
      CapturedMessages captured = capture(channelId, Optional.of(thread.threadTs()), page);
      progress = emit(progress.afterThreadPage(page.nextCursor()), captured, sink);
    }
    return progress;
  }

  /** 페이지를 그 뒤의 체크포인트와 함께 낸다. */
  private ChannelProgress emit(ChannelProgress progress, CapturedMessages captured, PageSink sink) {
    saved = saved.after(progress);
    sink.accept(captured.page(saved.toCheckpoint(json)));
    return progress;
  }

  private CapturedMessages capture(
      String channelId, Optional<SlackTs> thread, SlackWebApiClient.MessagePage page) {
    return new MessageCapture(
            json,
            clock,
            input.content(),
            input.source().id(),
            channelId,
            thread,
            config.historySince())
        .capture(page.messagesJson());
  }

  private List<String> joinedChannelIds() {
    Set<String> ids = new TreeSet<>();
    CursorTrail trail = new CursorTrail();
    Optional<String> cursor = Optional.empty();
    do {
      Optional<String> c = cursor;
      trail.request(c);
      SlackWebApiClient.ChannelPage page =
          call(
              "slack.users.conversations",
              t -> {
                SlackWebApiClient.ChannelPage listed =
                    client.listJoinedChannels(token, config.conversationTypes(), c, t);
                if (trail.revisits(listed.nextCursor()))
                  throw SlackApiException.cursorCycle(
                      trail.cycle("conversations", listed.nextCursor()));
                return listed;
              });
      ids.addAll(page.channelIds());
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return List.copyOf(ids);
  }

  /** 받은 다음 커서가 이 걷기에서 이미 요청한 커서면 위반(호출 안에서 던져 다른 응답 위반과 같이 분류된다). */
  private static SlackWebApiClient.MessagePage advancing(
      CursorTrail trail, String list, SlackWebApiClient.MessagePage page) {
    if (trail.revisits(page.nextCursor()))
      throw SlackApiException.cursorCycle(trail.cycle(list, page.nextCursor()));
    return page;
  }

  private <T> T call(String operation, CallExecutor.Call<T> call) {
    return input.calls().execute(operation, call, SlackErrors::classify, input.cancel());
  }
}
