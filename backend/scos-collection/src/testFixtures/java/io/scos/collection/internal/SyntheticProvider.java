package io.scos.collection.internal;

import io.scos.spi.connector.SourceAdapter;
import java.time.Instant;

/**
 * 커넥터 하나의 합성 공급자(TS fixture 데이터셋을 서빙하는 가짜 서버)와 그 위의 제품 어댑터, TS fixture 생성기와 같은 설정의 등록. 커넥터마다 {@code
 * <Kind>Assembly.synthetic(clock)}이 만든다 — 여러 커넥터를 한 런타임에서 돌리는 종단(F9 재수집 리허설)이 쓴다.
 *
 * @param collectedAt TS fixture 생성기가 이 데이터셋을 수집한 시각(데이터셋 `now`) — 같은 시각에 수집하면 관측 시각에서 나오는 값까지 같다
 * @param edit 공급자 쪽 내용 하나를 고친다(편집 각본이 없는 커넥터는 거부)
 */
public record SyntheticProvider(
    SourceInstance instance,
    SourceAdapter adapter,
    Instant collectedAt,
    Runnable edit,
    AutoCloseable server)
    implements AutoCloseable {
  /** 편집 각본이 없는 커넥터의 edit. */
  public static Runnable noEdit(String kind) {
    return () -> {
      throw new UnsupportedOperationException(kind + "에는 편집 각본이 없다");
    };
  }

  @Override
  public void close() throws Exception {
    server.close();
  }
}
