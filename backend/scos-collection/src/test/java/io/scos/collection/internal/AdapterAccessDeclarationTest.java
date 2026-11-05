package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.spi.connector.AccessModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("커넥터는 권한 운반 원본 종류를 명시한다")
class AdapterAccessDeclarationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
  private static final String LOCAL_URL = "http://127.0.0.1:1";
  private static final String SYNTHETIC_TOKEN = "synthetic-token";

  @Test
  @DisplayName("Drive는 파일과 드라이브만 권한 운반 원본으로 선언한다")
  void driveCarriersExcludeFragments() {
    // given
    var adapter =
        GoogleDriveAssembly.adapter(LOCAL_URL, GoogleDriveAssembly.CREDENTIAL_JSON, CLOCK);

    // when
    Set<String> carriers = adapter.accessPolicy().carrierKinds();

    // then
    assertThat(carriers).containsExactlyInAnyOrder("gdrive.file", "gdrive.drive");
  }

  @Test
  @DisplayName("Jira는 필드와 첨부를 제외한 문서 원본을 선언한다")
  void jiraCarriersExcludeFragments() {
    // given
    var adapter = JiraAssembly.adapter(LOCAL_URL, SYNTHETIC_TOKEN, CLOCK);

    // when
    Set<String> carriers = adapter.accessPolicy().carrierKinds();

    // then
    assertThat(carriers)
        .containsExactlyInAnyOrder(
            "jira.issue",
            "jira.project",
            "jira.component",
            "jira.version",
            "jira.filter",
            "jira.dashboard",
            "jira.board",
            "jira.sprint");
  }

  @Test
  @DisplayName("Slack은 메시지를 권한 운반 원본으로 선언한다")
  void slackDeclaresMessageCarrier() {
    // given
    var adapter = SlackAssembly.adapter(LOCAL_URL, SYNTHETIC_TOKEN, CLOCK);

    // when
    Set<String> carriers = adapter.accessPolicy().carrierKinds();

    // then
    assertThat(carriers).containsExactly("slack.message");
  }

  @Test
  @DisplayName("Tiro는 노트를 권한 운반 원본으로 선언한다")
  void tiroDeclaresNoteCarrier() {
    // given
    var adapter = TiroAssembly.adapter(LOCAL_URL, SYNTHETIC_TOKEN, CLOCK);

    // when
    Set<String> carriers = adapter.accessPolicy().carrierKinds();

    // then
    assertThat(carriers).containsExactly("tiro.note");
  }

  @Test
  @DisplayName("Notion은 접근 수집 미지원이며 권한 운반 원본이 없다")
  void notionIsUnsupportedWithoutRawCarriers() {
    // given
    var adapter = NotionAssembly.adapter(LOCAL_URL, SYNTHETIC_TOKEN, CLOCK);

    // when
    AccessModel model = adapter.accessPolicy().model();
    Set<String> carriers = adapter.accessPolicy().carrierKinds();

    // then
    assertThat(model).isEqualTo(AccessModel.UNSUPPORTED);
    assertThat(carriers).isEmpty();
  }
}
