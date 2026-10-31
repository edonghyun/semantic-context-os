package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("설정 변경은 본문 커밋의 설정 검증 잠금을 기다린다")
class PostgresConfigurationLockTest {
  @Test
  @DisplayName("다른 연결의 설정 변경은 본문 트랜잭션이 끝나야 진행한다")
  void configurationChangeWaitsForBodyTransaction() throws Exception {
    // given
    var context = new PostgresConfigurationRace();
    // when
    boolean blocked = context.changeDuringBodyTransaction();
    // then
    assertThat(blocked).isTrue();
  }
}
