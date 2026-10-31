package io.scos.connector.slack.internal;

import static io.scos.connector.slack.internal.SlackScenario.JSON;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.slack.internal.SlackScenario.Collection;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionMode;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 채널마다 어디부터 걷는가: historySince 경계, 워터마크 겹침, 저장 체크포인트(v1·v2)의 재개 지점, 경계 안팎의 거부. 요청 쿼리와 페이지로만 본다. */
@DisplayName("Slack 수집은 경계·겹침·체크포인트로 채널의 시작 지점을 정한다")
class SlackHistoryWindowTest {
  static final String FIRST_CHANNEL = "C0000000001";
  static final String SECOND_CHANNEL = "C0000000002";
  static final String THREAD_TS = "1760400100.000000";

  /** 첫 채널의 스레드 루트와 같은 시각(2025-10-14T00:01:40Z). */
  static final String SINCE_ISO = "2025-10-14T00:01:40Z";

  static final String SINCE_TS = "1760400100.000000";
  static final String WITH_SINCE =
      "{\"conversationTypes\":[\"public_channel\"],\"historySince\":\"" + SINCE_ISO + "\"}";
  static final int OVERLAP_SECONDS = 100;
  static final String WITH_OVERLAP =
      "{\"conversationTypes\":[\"public_channel\"],\"historyOverlapSeconds\":"
          + OVERLAP_SECONDS
          + "}";

  /** 첫 채널 완료 워터마크(1760400300)에서 겹침 100초를 뺀 시각. */
  static final String OVERLAP_OLDEST = "1760400200.000000";

  final SlackScenario slack = new SlackScenario();

  @AfterEach
  void tearDown() {
    slack.close();
  }

  @Nested
  @DisplayName("historySince가 있으면")
  class WithHistorySince {
    @Test
    @DisplayName("history를 경계부터 경계 포함으로 요청한다")
    void historyStartsAtBoundary() {
      // when
      slack.collectWithConfig(WITH_SINCE);

      // then
      assertThat(slack.historyRequestsSince(0))
          .first()
          .isEqualTo(
              "/api/conversations.history?channel="
                  + FIRST_CHANNEL
                  + "&oldest="
                  + SINCE_TS
                  + "&inclusive=true");
    }

    @Test
    @DisplayName("replies도 경계부터 경계 포함으로 요청한다")
    void repliesStartAtBoundary() {
      // when
      slack.collectWithConfig(WITH_SINCE);

      // then
      assertThat(slack.requests())
          .filteredOn(r -> r.startsWith("/api/conversations.replies"))
          .first()
          .isEqualTo(
              "/api/conversations.replies?channel="
                  + FIRST_CHANNEL
                  + "&ts="
                  + THREAD_TS
                  + "&oldest="
                  + SINCE_TS
                  + "&inclusive=true");
    }

    @Test
    @DisplayName("ts 없는 메시지는 본문을 담지 않고 스레드 없는 위치로 거부한다")
    void rejectsTimestamplessWithoutContent() {
      // when
      Collection collection = slack.collectWithConfig(WITH_SINCE);

      // then
      assertThat(collection.rejections())
          .singleElement()
          .extracting(
              r -> r.quarantined().isPresent(), r -> JSON.readTree(r.providerLocatorJson().get()))
          .containsExactly(
              false,
              JSON.readTree("{\"channelId\":\"" + SECOND_CHANNEL + "\",\"messageIndex\":1}"));
    }

    @Test
    @DisplayName("저장 체크포인트의 경계와 다르면 체크포인트를 버리고 경계부터 걷는다")
    void changedBoundaryDiscardsCheckpoint() {
      // given
      Checkpoint withoutSince = slack.givenCollected().finalCheckpoint();
      int mark = slack.requests().size();

      // when
      slack.collect(Optional.of(withoutSince), CollectionMode.INCREMENTAL, WITH_SINCE);

      // then
      assertThat(slack.historyRequestsSince(mark))
          .first()
          .asString()
          .contains("oldest=" + SINCE_TS);
    }
  }

  @Test
  @DisplayName("겹침이 있으면 완료 워터마크에서 겹침만큼 앞부터 경계 포함으로 요청한다")
  void overlapMovesOldestBack() {
    // given
    Checkpoint completed = slack.givenCollected().finalCheckpoint();
    int mark = slack.requests().size();

    // when
    slack.collect(Optional.of(completed), CollectionMode.INCREMENTAL, WITH_OVERLAP);

    // then
    assertThat(slack.historyRequestsSince(mark))
        .first()
        .isEqualTo(
            "/api/conversations.history?channel="
                + FIRST_CHANNEL
                + "&oldest="
                + OVERLAP_OLDEST
                + "&inclusive=true");
  }

  @Nested
  @DisplayName("저장 체크포인트의 진행 중 채널에서 이어 수집하면")
  class FromActiveChannel {
    static final String V1 =
        "{\"channelWatermarks\":{},\"activeChannel\":{\"channelId\":\""
            + FIRST_CHANNEL
            + "\",\"cursor\":\"page:1\",\"latestSeen\":\"1760400300.000000\"}}";
    static final String V2_WITH_THREAD =
        "{\"channelWatermarks\":{},\"activeChannel\":{\"channelId\":\""
            + FIRST_CHANNEL
            + "\",\"historyCursor\":\"page:1\",\"latestSeen\":\"1760400300.000000\","
            + "\"pendingThreads\":[{\"threadTs\":\""
            + THREAD_TS
            + "\"}]}}";

    @Test
    @DisplayName("v1이면 저장된 cursor의 history부터 걷는다")
    void v1ResumesAtCursor() {
      // when
      slack.collectFrom(new Checkpoint(1, V1));

      // then
      assertThat(slack.messageRequestsSince(0))
          .first()
          .isEqualTo("/api/conversations.history?channel=" + FIRST_CHANNEL + "&cursor=page:1");
    }

    @Test
    @DisplayName("v2에 남은 스레드가 있으면 그 스레드를 먼저 걷고 history 커서로 돌아간다")
    void v2ResumesPendingThreadFirst() {
      // when
      slack.collectFrom(new Checkpoint(2, V2_WITH_THREAD));

      // then
      assertThat(slack.messageRequestsSince(0))
          .startsWith(
              "/api/conversations.replies?channel=" + FIRST_CHANNEL + "&ts=" + THREAD_TS,
              "/api/conversations.replies?channel="
                  + FIRST_CHANNEL
                  + "&ts="
                  + THREAD_TS
                  + "&cursor=page:1",
              "/api/conversations.history?channel=" + FIRST_CHANNEL + "&cursor=page:1");
    }
  }

  @Nested
  @DisplayName("replies에 다른 스레드를 가리키는 답글이 섞이면")
  class StrayReply {
    static final String STRAY_TS = "1760400140.000000";

    @BeforeEach
    void givenStrayReply() {
      slack.withStrayReply(FIRST_CHANNEL, THREAD_TS, 1, STRAY_TS, "1760400999.000000");
    }

    @Test
    @DisplayName("그 답글을 요청한 스레드 위치로 거부한다")
    void rejectsStrayReply() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.rejections())
          .filteredOn(
              r -> r.message().equals("Slack thread reply must reference the requested thread"))
          .singleElement()
          .extracting(r -> JSON.readTree(r.providerLocatorJson().get()))
          .isEqualTo(
              JSON.readTree(
                  "{\"channelId\":\""
                      + FIRST_CHANNEL
                      + "\",\"threadTs\":\""
                      + THREAD_TS
                      + "\",\"messageIndex\":1}"));
    }

    @Test
    @DisplayName("그 답글은 원본으로 싣지 않는다")
    void strayReplyIsNotStored() {
      // when
      Collection collection = slack.collect();

      // then
      assertThat(collection.lastItems())
          .noneMatch(item -> item.externalId().equals(FIRST_CHANNEL + ":" + STRAY_TS));
    }
  }
}
