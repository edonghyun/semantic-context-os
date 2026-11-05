package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 설명 기동은 Flyway migrate 대신 validate만 실행한다(M-3)")
class AccessExplainConfigurationTest {
  @Test
  @DisplayName("새 마이그레이션 적용 없이 검증만 호출한다")
  void validatesWithoutMigrating() {
    var probe = new FlywayProbe();
    new AccessExplainConfiguration().accessExplainValidateOnly().migrate(probe);
    // then
    assertThat(probe.validated).isTrue();
  }

  private static final class FlywayProbe extends Flyway {
    private boolean validated;

    FlywayProbe() {
      super(Flyway.configure());
    }

    @Override
    public void validate() {
      validated = true;
    }
  }
}
