package io.scos.spi.connector;

import java.time.Duration;
import java.util.function.Function;

/**
 * 커넥터가 공급자를 부르는 계약(단계 2 API 설계 §1.2): 호출 하나를 받아 재시도·대기·취소를 맡는다. 제품 구현은 {@code
 * ProviderCallExecutor}다.
 */
public interface CallExecutor {
  /** 한 번의 공급자 호출. timeout은 이번 시도에 허용된 시간. */
  @FunctionalInterface
  interface Call<T> {
    T invoke(Duration timeout) throws Exception;
  }

  /** 호출을 실행한다. 실패는 classify로 분류해 {@link CallFailureException}으로 던진다. */
  <T> T execute(
      String operation,
      Call<T> call,
      Function<Exception, CallFailure> classify,
      CancellationToken cancel);

  /** 보통의 시도 timeout({@code ProviderHttp}가 이 값의 클라이언트를 쓴다). */
  Duration requestTimeout();
}
