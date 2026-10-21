package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.util.Optional;

/** 수집 보고 데이터(테스트 공용) — 다른 패키지 테스트가 11인자 생성자를 쓰지 않게 한다. */
public final class Reports {
  private Reports() {}

  /** 기록된 run 그대로의 보고. */
  public static CollectionRuntime.Report of(CollectionRun run) {
    return CollectionRuntime.Report.of(run);
  }

  /** run 기록 없이 만든 성공 보고(관측자처럼 보고만 읽는 대상용). */
  public static CollectionRuntime.Report succeeded(SourceInstanceId source) {
    return new CollectionRuntime.Report(
        new RunId("r-" + source.value()),
        source,
        RunStatus.SUCCEEDED,
        0,
        0,
        0,
        0,
        0,
        0,
        Optional.empty(),
        Optional.empty());
  }
}
