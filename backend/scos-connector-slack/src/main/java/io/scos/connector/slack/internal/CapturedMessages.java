package io.scos.connector.slack.internal;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.util.List;
import java.util.Optional;

/** 메시지 한 페이지를 가른 결과: 원본 변경, 거부, 새로 본 스레드 루트(처음 본 순서), 본 가장 늦은 ts. */
record CapturedMessages(
    List<RawMutation> mutations,
    List<RejectedItem> rejections,
    List<SlackTs> threadRoots,
    Optional<SlackTs> latestSeen) {
  CollectionPage page(Checkpoint next) {
    return new CollectionPage(mutations, rejections, next);
  }
}
