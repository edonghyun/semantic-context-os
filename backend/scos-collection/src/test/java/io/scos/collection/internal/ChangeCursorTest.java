package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** F10 커서 규칙의 경계값(CONTRACTS F10): 0 ≤ after ≤ head, 1 ≤ limit ≤ 1000, after < floor면 만료. */
@DisplayName("변경 로그 커서의 범위와 만료(CONTRACTS F10)")
class ChangeCursorTest {
  @ParameterizedTest(name = "after {0} limit {1} (floor {2} head {3})")
  @CsvSource({"-1, 10, 0, 10", "11, 10, 0, 10", "0, 0, 0, 10", "0, 1001, 0, 10"})
  @DisplayName("범위 밖 after·limit은 IllegalArgumentException")
  void rejectsOutOfRange(long after, int limit, long floor, long head) {
    // when·then
    assertThatThrownBy(() -> ChangeCursor.validate(after, limit, floor, head))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "after {0} limit {1} (floor {2} head {3})")
  @CsvSource({"0, 1, 0, 0", "10, 1000, 0, 10", "3, 1, 3, 10", "0, 1, 0, 10"})
  @DisplayName("범위 끝값의 after·limit과 after = floor는 통과한다")
  void acceptsBoundaries(long after, int limit, long floor, long head) {
    // when·then
    assertThatCode(() -> ChangeCursor.validate(after, limit, floor, head))
        .doesNotThrowAnyException();
  }

  @Nested
  @DisplayName("after가 보존 하한(floor)보다 작으면")
  class BelowFloor {
    @Test
    @DisplayName("ChangeCursorExpiredException이다")
    void expiredBelowFloor() {
      // when·then
      assertThatThrownBy(() -> ChangeCursor.validate(2, 10, 3, 10))
          .isInstanceOf(ChangeCursorExpiredException.class);
    }

    @Test
    @DisplayName("만료 예외는 보존 하한(floor)과 head를 알려준다")
    void expiryCarriesFloorAndHead() {
      // when
      ChangeCursorExpiredException expired =
          catchThrowableOfType(
              ChangeCursorExpiredException.class, () -> ChangeCursor.validate(2, 10, 3, 10));

      // then
      assertThat(expired.retainedAfter()).isEqualTo(3);
      assertThat(expired.head()).isEqualTo(10);
    }
  }
}
