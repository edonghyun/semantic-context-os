package io.scos.collection.internal.postgres;

import io.scos.collection.internal.SourceRegistrationAdminContract;
import io.scos.collection.internal.SourceRegistrationAdminScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("db")
@DisplayName("PG 수집 등록은 관리 원장을 갱신한다(T-c2)")
class SourceRegistrationAdminPostgresTest extends SourceRegistrationAdminContract {
  @Override
  protected SourceRegistrationAdminScenario newScenario() {
    PostgresSupport.truncateAll();
    return SourceRegistrationAdminScenario.postgres(PostgresSupport.dataSource());
  }
}
