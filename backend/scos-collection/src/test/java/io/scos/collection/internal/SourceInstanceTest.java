package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 커넥터 입력(stage3 T3): 어댑터는 소스의 런타임 필드(켜짐·주기·축소 보호·시각) 없이 다섯 필드만 본다. */
@DisplayName("descriptor는 어댑터가 쓰는 다섯 필드만 옮긴다(stage3 T3)")
class SourceInstanceTest {
  private static final Instant AT = Instant.parse("2026-10-19T00:00:00Z");

  @Test
  @DisplayName("id·종류·이름·설정·자격 증명 참조를 그대로 옮긴다")
  void copiesFiveFields() {
    // given
    SourceInstance source =
        SourceInstances.of(
            new SourceInstanceId("src-1"), "jira", "{\"site\":\"x\"}", "env:JIRA_TOKEN", AT);

    // when
    SourceDescriptor descriptor = source.descriptor();

    // then
    assertThat(descriptor)
        .isEqualTo(
            new SourceDescriptor(
                source.id(),
                source.kind(),
                source.displayName(),
                source.config(),
                source.credentialRef()));
  }

  @Test
  @DisplayName("이름이 null이면 빈 문자열로 바꾼다")
  void blankDisplayNameForNull() {
    // when
    SourceDescriptor descriptor =
        new SourceDescriptor(
            new SourceInstanceId("src-1"),
            "jira",
            null,
            new SourceConfig(1, "{}"),
            "env:JIRA_TOKEN");

    // then
    assertThat(descriptor.displayName()).isEmpty();
  }
}
