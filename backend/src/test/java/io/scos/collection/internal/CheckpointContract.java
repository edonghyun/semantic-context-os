package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 체크포인트 포트 계약(불변식 2의 저장 부분): revision 낙관 검사. */
@DisplayName("체크포인트는 기대 revision이 맞을 때만 전진한다(불변식 2)")
public abstract class CheckpointContract {
  protected static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  protected static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
  protected CheckpointPort port;

  protected abstract CheckpointPort newPort();

  /** JSONB 저장은 공백·키 순서를 바꿀 수 있으므로 파싱해 비교한다(TESTING §8). */
  static JsonNode json(String text) {
    return new ObjectMapper().readTree(text);
  }

  @BeforeEach
  void setUpContract() {
    port = newPort();
  }

  @Test
  @DisplayName("저장된 적이 없으면 revision 0, 체크포인트 없음")
  void emptyIsRevisionZero() {
    // when
    StoredCheckpoint stored = port.read(SOURCE);

    // then
    assertThat(stored).isEqualTo(StoredCheckpoint.none());
  }

  @Test
  @DisplayName("기대 revision이 맞으면 전진한 새 revision을 돌려준다")
  void advancesWhenRevisionMatches() {
    // when
    long r1 = port.advance(SOURCE, 0, new Checkpoint(2, "{\"after\":\"p1\"}"), NOW);
    long r2 = port.advance(SOURCE, 1, new Checkpoint(2, "{\"after\":\"p2\"}"), NOW);

    // then
    assertThat(r1).isEqualTo(1);
    assertThat(r2).isEqualTo(2);
  }

  @Test
  @DisplayName("전진한 뒤에는 마지막 revision과 값을 읽는다")
  void readsLastAdvance() {
    // given
    port.advance(SOURCE, 0, new Checkpoint(2, "{\"after\":\"p1\"}"), NOW);
    port.advance(SOURCE, 1, new Checkpoint(2, "{\"after\":\"p2\"}"), NOW);

    // when
    StoredCheckpoint stored = port.read(SOURCE);

    // then
    assertThat(stored.revision()).isEqualTo(2);
    assertThat(stored.checkpoint().map(Checkpoint::valueJson).map(CheckpointContract::json))
        .contains(json("{\"after\":\"p2\"}"));
  }

  @Nested
  @DisplayName("src-a가 revision 1(p1)까지 전진한 상태에서")
  class AdvancedOnce {
    @BeforeEach
    void givenAdvanced() {
      port.advance(SOURCE, 0, new Checkpoint(2, "{\"after\":\"p1\"}"), NOW);
    }

    @Test
    @DisplayName("이미 지난 revision으로 전진하면 충돌로 거부된다")
    void rejectsPastRevision() {
      // when·then
      assertThatThrownBy(
              () -> port.advance(SOURCE, 0, new Checkpoint(2, "{\"after\":\"dup\"}"), NOW))
          .isInstanceOf(CheckpointConflictException.class);
    }

    @Test
    @DisplayName("저장된 적 없는 미래 revision으로 전진하면 충돌로 거부된다")
    void rejectsFutureRevision() {
      // when·then
      assertThatThrownBy(
              () -> port.advance(SOURCE, 5, new Checkpoint(2, "{\"after\":\"future\"}"), NOW))
          .isInstanceOf(CheckpointConflictException.class);
    }

    @Test
    @DisplayName("충돌한 전진은 거부되고, 저장 상태를 바꾸지 않는다")
    void conflictKeepsStoredCheckpoint() {
      // when
      Throwable conflict =
          catchThrowable(
              () -> port.advance(SOURCE, 0, new Checkpoint(2, "{\"after\":\"dup\"}"), NOW));

      // then
      assertThat(conflict).isInstanceOf(CheckpointConflictException.class);
      StoredCheckpoint stored = port.read(SOURCE);
      assertThat(stored)
          .extracting(
              StoredCheckpoint::revision,
              s -> s.checkpoint().map(Checkpoint::valueJson).map(CheckpointContract::json))
          .containsExactly(1L, Optional.of(json("{\"after\":\"p1\"}")));
    }
  }

  @Test
  @DisplayName("소스별로 독립된 revision을 가진다")
  void sourcesIndependent() {
    // given
    port.advance(SOURCE, 0, new Checkpoint(1, "{}"), NOW);

    // when
    StoredCheckpoint other = port.read(new SourceInstanceId("src-b"));

    // then
    assertThat(other).isEqualTo(new StoredCheckpoint(0, Optional.empty()));
  }
}
