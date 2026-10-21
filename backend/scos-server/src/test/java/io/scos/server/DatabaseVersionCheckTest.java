package io.scos.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.server.DatabaseVersionCheck.Expected;
import io.scos.server.DatabaseVersionCheck.Observed;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기동 시 DB 버전 검사(T10.1, ADR-0027): 서버 major.minor와 pgvector 확장 버전이 기대값과 하나라도 다르면 기동을 거부한다. 실제 컨테이너의
 * 값은 {@link DatabaseVersionCheckPostgresTest}가 본다.
 */
@DisplayName("기동 시 DB 서버·pgvector 버전이 기대와 다르면 거부한다(ADR-0027)")
class DatabaseVersionCheckTest {
  static final Expected EXPECTED = new Expected("17.11", "0.8.7");

  static DatabaseVersionCheck observing(String server, Optional<String> vector) {
    return new DatabaseVersionCheck(EXPECTED, () -> new Observed(server, vector));
  }

  @Test
  @DisplayName("둘 다 맞으면 통과한다")
  void passesWhenBothMatch() {
    // when·then
    assertThatCode(() -> observing("17.11", Optional.of("0.8.7")).verify())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("서버 버전이 다르면 기동을 거부한다")
  void rejectsServerMismatch() {
    // when·then
    assertThatThrownBy(() -> observing("17.9", Optional.of("0.8.7")).verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("server 17.9 (expected 17.11)");
  }

  @Test
  @DisplayName("pgvector 버전이 다르면 기동을 거부한다")
  void rejectsVectorMismatch() {
    // when·then
    assertThatThrownBy(() -> observing("17.11", Optional.of("0.8.6")).verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vector 0.8.6 (expected 0.8.7)");
  }

  @Test
  @DisplayName("pgvector 확장이 없으면 기동을 거부한다")
  void rejectsMissingVector() {
    // when·then
    assertThatThrownBy(() -> observing("17.11", Optional.empty()).verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vector missing (expected 0.8.7)");
  }
}
