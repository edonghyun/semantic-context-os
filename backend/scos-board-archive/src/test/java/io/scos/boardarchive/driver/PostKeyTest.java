package io.scos.boardarchive.driver;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("글 키와 HTTP 응답은 순서와 실패 상태를 보존한다")
class PostKeyTest {
  @Test
  @DisplayName("오래된 시각이 먼저 온다")
  void olderTimeSortsFirst() {
    // then
    assertThat(new PostKey(Instant.EPOCH, "z"))
        .isLessThan(new PostKey(Instant.EPOCH.plusSeconds(1), "a"));
  }

  @Test
  @DisplayName("같은 시각이면 글 id 사전순이다")
  void sameTimeOrdersByPostId() {
    // then
    assertThat(new PostKey(Instant.EPOCH, "a")).isLessThan(new PostKey(Instant.EPOCH, "b"));
  }

  @Test
  @DisplayName("비정상 응답은 상태 코드가 있는 예외다")
  void responseRequireOkThrowsWithStatus() {
    // then
    assertThatThrownBy(() -> new Response(403, "").requireOk())
        .isInstanceOfSatisfying(
            HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(403));
  }
}
