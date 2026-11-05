package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("저장 경계는 수집 시작의 설정 세대를 확인한다")
public abstract class ConfigurationFenceContract {
  protected ConfigurationFenceScenario context;

  protected abstract ConfigurationFenceScenario newContext();

  @BeforeEach
  void prepare() {
    context = newContext();
  }

  @Test
  @DisplayName("설정 변경 뒤 옛 본문 완료는 이전 블록을 정리하지 못한다")
  void changedConfigurationRejectsBodyCompletion() {
    // given
    context.previousBody();
    context.changeConfiguration();
    // when·then
    assertThatThrownBy(context::completeBody)
        .isInstanceOf(SourceConfigurationChangedException.class);
    assertThat(context.removedBlockIsActive()).isTrue();
  }

  @Test
  @DisplayName("설정 변경 뒤 옛 전체 완료는 정리 성공을 기록하지 못한다")
  void changedConfigurationRejectsSnapshotCompletion() {
    // given
    context.previousBody();
    context.changeConfiguration();
    // when·then
    context.completeSnapshot();
    assertThat(context.snapshotCompleted()).isFalse();
  }
}
