package io.scos.connector.slack.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 이번 run이 걸을 채널: 설정한 채널(모두 참여 중이어야 한다) 또는 참여한 채널 전부. */
record ChannelSelection(List<String> channelIds) {
  static ChannelSelection of(SlackConfiguration config, List<String> joined) {
    config.channelIds().ifPresent(configured -> requireJoined(configured, Set.copyOf(joined)));
    return new ChannelSelection(config.channelIds().orElse(joined));
  }

  private static void requireJoined(List<String> configured, Set<String> joined) {
    List<String> missing = configured.stream().filter(id -> !joined.contains(id)).toList();
    if (!missing.isEmpty())
      throw new CallFailureException(
          "slack.configuration",
          CallFailure.of(
              CallFailure.Kind.INVALID_CONFIGURATION,
              "Configured Slack channels are not joined: " + String.join(", ", missing)));
  }

  /** 진행 중이던 채널이 목록에 있으면 그 채널부터, 아니면 처음부터. */
  List<String> resumingAt(Optional<String> activeChannelId) {
    int index = activeChannelId.map(channelIds::indexOf).orElse(-1);
    return index >= 0 ? channelIds.subList(index, channelIds.size()) : channelIds;
  }
}
