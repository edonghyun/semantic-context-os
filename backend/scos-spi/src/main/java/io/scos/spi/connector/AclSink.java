package io.scos.spi.connector;

/**
 * 접근 관측 페이지를 저장한다. 빈 페이지도 허용하며 저장 실패 예외는 수집을 중단시킨다. 호출은 같은 수집 스레드에서 순차적으로 해야 하며 병렬 호출은 지원하지 않는다.
 */
@FunctionalInterface
public interface AclSink {
  void accept(AclPage page);
}
