package io.scos.connector.slack.internal;

import java.util.Optional;

/**
 * 채널 history의 시작 지점: 워터마크에서 겹침만큼 앞당긴 시각과 historySince 중 늦은 쪽. 겹침이 있거나 경계에서 바로 시작하면 그 시각의 메시지도 포함한다.
 */
record ChannelStart(Optional<SlackTs> oldest, boolean inclusive) {
  static ChannelStart of(Optional<SlackTs> watermark, SlackConfiguration config) {
    Optional<SlackTs> overlap = watermark.map(w -> w.minusSeconds(config.historyOverlapSeconds()));
    Optional<SlackTs> oldest = SlackTs.later(overlap, config.historySince());
    boolean inclusive =
        oldest.isPresent()
            && (config.historyOverlapSeconds() > 0 || oldest.equals(config.historySince()));
    return new ChannelStart(oldest, inclusive);
  }

  Optional<String> oldestParam() {
    return oldest.map(SlackTs::text);
  }

  Optional<Boolean> inclusiveParam() {
    return inclusive ? Optional.of(true) : Optional.empty();
  }
}
