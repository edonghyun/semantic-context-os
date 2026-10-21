package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 소스 → 공간(A5): 명시 매핑(SCOS_SOURCE_SPACES)이 있으면 그 공간, 없으면 기본 공간. Slack DM과 Tiro(회의록)는 명시 매핑 없이는 투영하지
 * 않는다 — 기본 공간에 섞이지 않게. TS는 SCOS_SPACE_ID만 있어도 받지만 Java 기본 공간은 SCOS_SPACE_ID 미설정과 구분되지 않아(기본값
 * legacy) 소스별 매핑을 요구한다.
 */
@DisplayName("소스는 명시 매핑 공간, 없으면 기본 공간에 투영하고 DM·Tiro는 매핑을 요구한다(A5)")
class ProjectionSpacesTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant AT = Instant.parse("2026-10-15T00:00:00Z");
  static final SpaceId DEFAULT = new SpaceId("legacy");
  static final SpaceId MEETINGS = new SpaceId("meetings");
  static final SourceInstance TIRO =
      SourceInstances.of(
          new SourceInstanceId("tiro-a"),
          "tiro",
          "{\"workspaceGuid\":\"w\",\"allNotes\":true}",
          "env:K",
          AT);
  static final SourceInstance SLACK_DM =
      SourceInstances.of(
          new SourceInstanceId("slack-dm"),
          "slack",
          "{\"conversationTypes\":[\"im\"],\"channelIds\":[\"D1\"]}",
          "env:K",
          AT);
  static final SourceInstance SLACK_PUBLIC_AND_DM =
      SourceInstances.of(
          new SourceInstanceId("slack-mixed"),
          "slack",
          "{\"conversationTypes\":[\"public_channel\",\"im\"]}",
          "env:K",
          AT);
  static final SourceInstance SLACK =
      SourceInstances.of(
          new SourceInstanceId("slack-a"),
          "slack",
          "{\"conversationTypes\":[\"public_channel\"]}",
          "env:K",
          AT);

  static ProjectionSpaces mapping(Map<String, SpaceId> explicit) {
    return new ProjectionSpaces(explicit, DEFAULT, JSON);
  }

  @Test
  @DisplayName("Tiro는 명시 매핑이 없으면 투영 공간을 정하지 않는다")
  void tiroRequiresExplicitSpace() {
    // given
    ProjectionSpaces spaces = mapping(Map.of());

    // when·then
    assertThatThrownBy(() -> spaces.resolve(TIRO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tiro-a")
        .hasMessageContaining("SCOS_SOURCE_SPACES");
  }

  @Test
  @DisplayName("Tiro는 명시 매핑의 공간으로 투영한다")
  void tiroUsesExplicitSpace() {
    // given
    ProjectionSpaces spaces = mapping(Map.of("tiro-a", MEETINGS));

    // when·then
    assertThat(spaces.resolve(TIRO)).isEqualTo(MEETINGS);
  }

  @Test
  @DisplayName("Slack DM은 명시 매핑이 없으면 투영 공간을 정하지 않는다")
  void slackDmRequiresExplicitSpace() {
    // given
    ProjectionSpaces spaces = mapping(Map.of());

    // when·then
    assertThatThrownBy(() -> spaces.resolve(SLACK_DM)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("공개 채널과 DM을 함께 가진 Slack은 명시 매핑이 없으면 투영 공간을 정하지 않는다")
  void slackPublicAndDmRequiresExplicitSpace() {
    // given
    ProjectionSpaces spaces = mapping(Map.of());

    // when·then
    assertThatThrownBy(() -> spaces.resolve(SLACK_PUBLIC_AND_DM))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("slack-mixed");
  }

  @Test
  @DisplayName("공개 채널 Slack은 매핑이 없으면 기본 공간이다")
  void publicSlackUsesDefault() {
    // given
    ProjectionSpaces spaces = mapping(Map.of("tiro-a", MEETINGS));

    // when·then
    assertThat(spaces.resolve(SLACK)).isEqualTo(DEFAULT);
  }
}
