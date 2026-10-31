package io.scos.engine.internal.search.plan;

import static io.scos.engine.Effort.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.Effort;
import io.scos.engine.InvalidQueryException;
import io.scos.engine.Profile;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 강도·프로필 해석과 CONTRACTS B8 최신성 표지 판정 표. 고정 시각 2026-10-13(10월). */
@DisplayName("PlanResolver는 강도·프로필·질의 문구로 검색 계획을 정한다")
class PlanResolverTest {
  static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

  final PlanResolver resolver = new PlanResolver();

  boolean searchRecency(String query) {
    return resolver.forSearch(query, MEDIUM, Profile.AUTO, NOW).recencyBoost();
  }

  @Test
  @DisplayName("search의 AUTO는 질의 문구로 날짜 가산을 정한다")
  void searchAutoUsesQueryWording() {
    // when
    boolean recent = searchRecency("최근 배포 이슈");
    boolean plain = searchRecency("배포 가이드");

    // then
    assertThat(recent).isTrue();
    assertThat(plain).isFalse();
  }

  @Test
  @DisplayName("CURRENT는 질의와 무관하게 날짜 가산을 켠다")
  void currentAlwaysBoosts() {
    // when
    RetrievalPlan plan = resolver.forSearch("배포 가이드", MEDIUM, Profile.CURRENT, NOW);

    // then
    assertThat(plan.recencyBoost()).isTrue();
  }

  @Test
  @DisplayName("assemble의 AUTO는 날짜 가산을 항상 켠다")
  void assembleAutoBoosts() {
    // when
    RetrievalPlan plan = resolver.forAssemble("배포 가이드", MEDIUM, Profile.AUTO, NOW);

    // then
    assertThat(plan.recencyBoost()).isTrue();
  }

  @Test
  @DisplayName("강도의 프리셋과 강도를 계획에 담는다")
  void carriesEffortPreset() {
    // when
    RetrievalPlan plan = resolver.forSearch("정산", MEDIUM, Profile.AUTO, NOW);

    // then
    assertThat(plan.effort()).isEqualTo(MEDIUM);
    assertThat(plan.settings()).isEqualTo(Presets.of(MEDIUM));
  }

  @Test
  @DisplayName("AS-OF는 아직 지원하지 않아 search·assemble 모두 거부한다")
  void asOfRejected() {
    // given
    Profile asOf = new Profile.AsOf(NOW);

    // when·then
    assertThatThrownBy(() -> resolver.forSearch("정산", MEDIUM, asOf, NOW))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessageContaining("as-of is not supported yet");
    assertThatThrownBy(() -> resolver.forAssemble("정산", MEDIUM, asOf, NOW))
        .isInstanceOf(InvalidQueryException.class);
  }

  @ParameterizedTest(name = "\"{0}\" → {1}")
  @CsvSource({
    "최근 정산 현황, true",
    "요즘 결제 장애, true",
    "이번 주 배포, true",
    "이번주 배포, true",
    "이번 달의 지표, true",
    "latest incident, true",
    "this month report, true",
    "주간 보고, true",
    "weekly report, true",
    "일간 지표, true",
    "정산 현황, false",
    "배포 방법, false",
    "온보딩 가이드, false",
    "deployment guide, false",
    "2025년 결제 장애, false",
    "작년 정산, false",
    "지난 분기 매출, false",
    "last incident, false",
    "10월 15일 회의, false",
    "10/15 회의, false",
    "최근 10월 배포, true",
    "최근 9월 배포, false",
    "최근 배포 방법, true",
    "이번 주 배포 방법, true",
    "주간 보고 작성법, false"
  })
  @DisplayName("질의의 최신성 표지를 판정한다")
  void prefersRecentTable(String query, boolean expected) {
    // when: 과거·다른 달·특정일·방법류는 false, 최근 표지·정기 보고는 true
    boolean recent = searchRecency(query);

    // then
    assertThat(recent).isEqualTo(expected);
  }

  @Test
  @DisplayName("강도를 올려도 질의가 정한 날짜 가산은 그대로다")
  void escalateKeepsRecencyDecision() {
    // given
    RetrievalPlan plain = resolver.forSearch("배포 가이드", Effort.LOW, Profile.AUTO, NOW);
    RetrievalPlan recent = resolver.forSearch("최근 배포 이슈", Effort.LOW, Profile.AUTO, NOW);

    // when
    RetrievalPlan plainUp = resolver.escalate(plain, MEDIUM);
    RetrievalPlan recentUp = resolver.escalate(recent, MEDIUM);

    // then
    assertThat(plainUp.recencyBoost()).isFalse();
    assertThat(recentUp.recencyBoost()).isTrue();
  }

  @Test
  @DisplayName("강도를 올리면 설정과 재정렬이 그 강도의 프리셋이 된다")
  void escalateTakesTargetPreset() {
    // given
    RetrievalPlan low = resolver.forSearch("배포 가이드", Effort.LOW, Profile.AUTO, NOW);

    // when
    RetrievalPlan up = resolver.escalate(low, MEDIUM);

    // then
    assertThat(up).isEqualTo(resolver.forSearch("배포 가이드", MEDIUM, Profile.AUTO, NOW));
  }
}
