package io.scos.spi.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("실제 그룹은 합성 그룹의 예약 접두를 사용할 수 없다")
class GroupSnapshotTest {
  private static final Instant AT = Instant.parse("2026-11-03T00:00:00Z");

  @Test
  @DisplayName("실제 그룹은 합성 그룹처럼 이름 지을 수 없다")
  void realGroupNamedLikeSyntheticIsRejected() {
    // when·then
    assertThatThrownBy(() -> new GroupSnapshot("~project:10000", Set.of("U1"), AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("합성 그룹은 명시적인 팩터리로 생성한다")
  void syntheticGroupIsExplicit() {
    // when
    var group = GroupSnapshot.synthetic("~project:10000", Set.of("U1"), AT);
    // then
    assertThat(group.kind()).isEqualTo(GroupSnapshot.Kind.SYNTHETIC);
  }

  @Test
  @DisplayName("합성 그룹도 예약 접두가 반드시 있어야 한다")
  void syntheticGroupRequiresReservedPrefix() {
    // when·then
    assertThatThrownBy(() -> GroupSnapshot.synthetic("project:10000", Set.of("U1"), AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("관측 변경은 합성 그룹 구분을 보존한다")
  void changedObservationPreservesSyntheticKind() {
    var group = GroupSnapshot.synthetic("~project:10000", Set.of("U1"), AT);
    // when
    var changed = group.withObservation(Set.of(), AT.plusSeconds(1));
    // then
    assertThat(changed)
        .isEqualTo(GroupSnapshot.synthetic("~project:10000", Set.of(), AT.plusSeconds(1)));
  }
}
