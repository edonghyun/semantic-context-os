package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 테스트 이미지(digest 고정)가 운영 기대값과 같은 버전이다 — 기본 기대값이 실제 이미지에서 기동을 막지 않는다(ADR-0027). */
@Tag("db")
@DisplayName("테스트 이미지의 DB 버전은 운영 기대값과 같다(ADR-0027)")
class DatabaseVersionCheckPostgresTest {
  @Test
  @DisplayName("테스트 이미지는 PostgreSQL 17.11과 pgvector 0.8.7이다")
  void imageMatchesDefaults() {
    // when
    var observed = DatabaseVersionCheck.observe(PostgresSupport.dataSource());

    // then: 기본 기대값과 같은지는 PostgresVersionPinTest가 따로 본다
    assertThat(observed)
        .isEqualTo(new DatabaseVersionCheck.Observed("17.11", Optional.of("0.8.7")));
  }
}
