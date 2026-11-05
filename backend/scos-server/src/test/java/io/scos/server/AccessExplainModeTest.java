package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

@DisplayName("접근 설명 명령은 마이그레이션 없는 읽기 전용 모드로 뜬다(O-1·M-3)")
class AccessExplainModeTest {
  @Test
  @DisplayName("source access explain은 읽기 전용 프로필을 켠다")
  void explainUsesReadOnlyProfile() {
    var app = new SpringApplication(ScosApplication.class);
    LaunchMode.of(new String[] {"source", "access", "explain", "doc"}).configure(app);
    // then
    assertThat(app.getAdditionalProfiles()).containsExactly("access-explain");
  }

  @Test
  @DisplayName("다른 소스 명령은 기존 명령 모드를 유지한다")
  void otherSourceCommandsKeepCommandMode() {
    var mode = LaunchMode.of(new String[] {"source", "status", "drive-a"});
    // then
    assertThat(mode.name()).isEqualTo("COMMAND");
  }
}
