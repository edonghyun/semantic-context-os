package io.scos.spi.access;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;

@DisplayName("기록용 원천 최신성은 실제 구현과 같은 계약을 지킨다(B9)")
class RecordedSourceFreshnessTest extends SourceFreshnessContract {
  private RecordedSourceFreshness recorded;

  @Override
  protected SourceFreshness newFreshness() {
    recorded = new RecordedSourceFreshness();
    return recorded;
  }

  @Override
  protected void recordSuccess(SourceInstanceId instance, Instant finishedAt) {
    recorded.recordSuccess(instance, finishedAt);
  }

  @Override
  protected void recordNonSuccess(SourceInstanceId instance, Instant finishedAt) {
    recorded.recordNonSuccess(instance, finishedAt);
  }
}
