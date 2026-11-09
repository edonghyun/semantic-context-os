package io.scos.spi.connector;

/**
 * 접근 관측 페이지를 저장한다. 빈 페이지도 허용하며 저장 실패 예외는 수집을 중단시킨다. 호출은 같은 수집 스레드에서 순차적으로 해야 하며 병렬 호출은 지원하지 않는다.
 */
public interface AclSink {
  void accept(AclPage page);

  /** 실제 접근 호출 수를 보고한다. 실패한 시도도 finally에서 전달하며 저장 성공을 뜻하지 않는다. */
  void cost(AccessCost cost);
}
