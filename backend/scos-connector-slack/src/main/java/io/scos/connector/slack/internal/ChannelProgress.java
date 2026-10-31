package io.scos.connector.slack.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 진행 중인 채널 하나: 다음 history 커서, 지금까지 본 가장 늦은 ts, 마지막 history 페이지가 연 스레드 중 아직 걷지 않은 것(앞에서부터 걷는다). 페이지를
 * 걸을 때마다 새 값이 된다.
 */
record ChannelProgress(
    String channelId,
    Optional<String> historyCursor,
    Optional<SlackTs> latestSeen,
    List<ThreadCursor> pendingThreads) {
  record ThreadCursor(SlackTs threadTs, Optional<String> cursor) {}

  ChannelProgress {
    pendingThreads = List.copyOf(pendingThreads);
  }

  /** 처음 걷는 채널: 워터마크까지는 이미 봤다. */
  static ChannelProgress starting(String channelId, Optional<SlackTs> watermark) {
    return new ChannelProgress(channelId, Optional.empty(), watermark, List.of());
  }

  /** history 한 페이지 뒤: 다음 커서로 가고, 그 페이지가 연 스레드를 새로 걸을 차례로 둔다. */
  ChannelProgress afterHistoryPage(Optional<String> next, CapturedMessages captured) {
    return new ChannelProgress(
        channelId,
        next,
        SlackTs.later(latestSeen, captured.latestSeen()),
        captured.threadRoots().stream().map(ts -> new ThreadCursor(ts, Optional.empty())).toList());
  }

  Optional<ThreadCursor> nextThread() {
    return pendingThreads.stream().findFirst();
  }

  /** 맨 앞 스레드의 replies 한 페이지 뒤: 다음 커서가 있으면 그 자리에 두고, 없으면 그 스레드를 끝낸다. */
  ChannelProgress afterThreadPage(Optional<String> next) {
    List<ThreadCursor> rest = new ArrayList<>(pendingThreads.subList(1, pendingThreads.size()));
    next.ifPresent(cursor -> rest.add(0, new ThreadCursor(pendingThreads.get(0).threadTs(), next)));
    return new ChannelProgress(channelId, historyCursor, latestSeen, rest);
  }

  boolean finished() {
    return historyCursor.isEmpty() && pendingThreads.isEmpty();
  }
}
