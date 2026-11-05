package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("원본 ACL은 비관측과 관측된 UNKNOWN을 구분한다(수집 §5.1)")
class RawAclTest {
  private static final Instant OBSERVED = Instant.parse("2026-10-14T00:00:00Z");
  private static final AccessKey USER = AccessKey.parse("user:slack-a:U1");

  @Test
  @DisplayName("관측하지 않은 ACL은 관측된 UNKNOWN이 아니다")
  void notObservedIsNotObservedUnknown() {
    // when
    RawAcl acl = RawAcl.notObserved();

    // then
    assertThat(acl).isInstanceOf(RawAcl.NotObserved.class).isNotInstanceOf(RawAcl.Observed.class);
  }

  @Test
  @DisplayName("관측된 UNKNOWN은 키 없는 관측값이다")
  void observedUnknownHasNoKeys() {
    // when
    var acl = RawAcl.observed(DocumentAcl.Status.UNKNOWN, Set.of(), OBSERVED);

    // then
    assertThat(acl.status()).isEqualTo(DocumentAcl.Status.UNKNOWN);
    assertThat(acl.keys()).isEmpty();
    assertThat(acl.observedAt()).isEqualTo(OBSERVED);
  }

  @Test
  @DisplayName("내용을 교체하면 기존 ACL을 보존한다")
  void withContentPreservesAcl() {
    // given
    var acl = RawAcl.observed(DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    var item = RawItems.item("C1:1700000000.000001").acl(acl).build();

    // when
    RawItem changed =
        item.withContent(new RawContent.Inline("application/json", "{\"text\":\"new\"}"));

    // then
    assertThat(changed.acl()).isSameAs(acl);
  }

  @Test
  @DisplayName("ACL이 null이면 원본 생성을 거부한다")
  void nullAclIsRejected() {
    // when·then
    assertThatThrownBy(() -> RawItems.item("C1:1700000000.000001").acl(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("acl must not be null");
  }

  @Test
  @DisplayName("입력 키 집합이 바뀌어도 관측값의 키는 유지된다")
  void observationCopiesKeys() {
    // given
    var keys = new HashSet<>(Set.of(USER));
    var acl = RawAcl.observed(DocumentAcl.Status.OK, keys, OBSERVED);

    // when
    keys.clear();

    // then
    assertThat(acl.keys()).containsExactly(USER);
  }

  @Test
  @DisplayName("관측 상태가 null이면 생성을 거부한다")
  void nullStatusIsRejected() {
    // when·then
    assertThatThrownBy(() -> RawAcl.observed(null, Set.of(), OBSERVED))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("status");
  }

  @Test
  @DisplayName("관측 시각이 null이면 생성을 거부한다")
  void nullObservationTimeIsRejected() {
    // when·then
    assertThatThrownBy(() -> RawAcl.observed(DocumentAcl.Status.OK, Set.of(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("observedAt");
  }
}
