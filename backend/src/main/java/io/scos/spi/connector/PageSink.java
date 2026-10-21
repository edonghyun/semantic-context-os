package io.scos.spi.connector;

/** 어댑터가 만든 페이지를 받는 쪽(런타임: heartbeat 검사 + 페이지 커밋). 던지면 수집이 중단된다. */
@FunctionalInterface
public interface PageSink {
  void accept(CollectionPage page);
}
