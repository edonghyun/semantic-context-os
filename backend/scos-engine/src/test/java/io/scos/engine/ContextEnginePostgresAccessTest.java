package io.scos.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("db")
@DisplayName("Postgres 엔진은 요청자가 볼 수 있는 문서만 내보낸다")
class ContextEnginePostgresAccessTest extends ContextEngineAccessContract {
  @Override
  protected AccessScenario scenario() {
    return new AccessScenario(new PostgresAccessAdapters()::create);
  }
}
