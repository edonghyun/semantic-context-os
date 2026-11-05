package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("접근 관측 값은 필수 필드가 없는 입력을 생성 시 거부한다")
class AccessRecordsTest {
  private static final Instant AT = Instant.parse("2026-10-14T00:00:00Z");

  @ParameterizedTest
  @ValueSource(strings = {"groupId", "observedAt"})
  @DisplayName("그룹 식별자와 관측 시각은 null일 수 없다")
  void groupRejectsMissingField(String field) {
    // when·then
    assertThatThrownBy(
            () ->
                new GroupSnapshot(
                    field.equals("groupId") ? null : "C1",
                    Set.of("U1"),
                    field.equals("observedAt") ? null : AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage(field);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sourceUserId", "email", "observedAt"})
  @DisplayName("신원 식별자와 이메일 Optional과 관측 시각은 null일 수 없다")
  void identityRejectsMissingField(String field) {
    // when·then
    assertThatThrownBy(
            () ->
                new SourceIdentityRecord(
                    field.equals("sourceUserId") ? null : "U1",
                    field.equals("email") ? null : Optional.of("u1@example.com"),
                    true,
                    false,
                    false,
                    field.equals("observedAt") ? null : AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage(field);
  }
}
