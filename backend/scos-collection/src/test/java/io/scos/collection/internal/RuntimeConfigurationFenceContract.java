package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("수집 런타임은 시작 설정을 저장 경계에 전달한다")
public abstract class RuntimeConfigurationFenceContract {
  protected abstract RuntimeConfigurationFenceScenario newContext();

  @Test
  @DisplayName("페이지 사이 설정이 바뀌면 옛 본문 정리를 설정 변경으로 중단한다")
  void changedConfigurationStopsRuntimeBodyCompletion() {
    // given
    var context = newContext();
    // when
    var report = context.collect();
    // then
    assertThat(report.errorSummary()).contains("Source configuration changed during collection");
    assertThat(context.removedBlockIsActive()).isTrue();
  }

  @Test
  @DisplayName("설정 변경 중단은 고정된 설정 변경 오류 종류로 보고한다")
  void reportsConfigurationChangeKind() {
    // given
    var context = newContext();
    // when
    var report = context.collect();
    // then
    assertThat(report.errorKind()).contains("source_configuration_changed");
  }

  @Test
  @DisplayName("모든 페이지 뒤 설정이 바뀌어도 옛 전체의 정리 완료를 기록하지 않는다")
  void changedConfigurationStopsRuntimeSnapshotCompletion() {
    // given
    var context = newContext();
    context.changeBeforeSnapshotReturns();
    // when
    var report = context.collect();
    // then
    assertThat(report.errorKind()).contains("source_configuration_changed");
    assertThat(context.snapshotCompleted()).isFalse();
  }
}
