package io.scos.client.source.transcript;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 완전한 줄 단위로 페이지를 쌓고 상한에서 내보낸다. 체크포인트는 실제 방출 시에만 계산한다. */
final class PageBuffer {
  private static final int PAGE_LINES = 1000;
  private static final long PAGE_BYTES = 8L << 20;
  private final PageSink sink;
  private List<RawMutation> mutations = new ArrayList<>();
  private List<RejectedItem> rejections = new ArrayList<>();
  private long lines;
  private long bytes;

  PageBuffer(PageSink sink) {
    this.sink = sink;
  }

  void add(TranscriptLineCapture.Outcome outcome) {
    switch (outcome) {
      case TranscriptLineCapture.Accepted accepted -> {
        mutations.add(new RawMutation.Upsert(accepted.item()));
        bytes += accepted.bytes();
      }
      case TranscriptLineCapture.Rejected rejected -> rejections.add(rejected.item());
    }
    lines++;
  }

  void add(RawMutation mutation) {
    mutations.add(mutation);
  }

  void add(RejectedItem rejection) {
    rejections.add(rejection);
  }

  void flushIfFull(Supplier<Checkpoint> checkpoint) {
    if (lines >= PAGE_LINES || bytes >= PAGE_BYTES) flush(checkpoint.get());
  }

  void flush(Checkpoint checkpoint) {
    if (!mutations.isEmpty() || !rejections.isEmpty()) {
      sink.accept(new CollectionPage(mutations, rejections, checkpoint));
    }
    discard();
  }

  /** 읽기 실패 시 아직 방출하지 않은 변경만 버린다. 이미 넘긴 페이지의 목록은 건드리지 않는다. */
  void discard() {
    mutations = new ArrayList<>();
    rejections = new ArrayList<>();
    lines = 0;
    bytes = 0;
  }
}
