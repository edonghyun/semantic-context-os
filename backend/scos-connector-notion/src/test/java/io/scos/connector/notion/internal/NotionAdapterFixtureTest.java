package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.TsRawViews.CheckpointView;
import io.scos.connector.notion.internal.NotionScenario.Collection;
import io.scos.connector.notion.internal.NotionScenario.Config;
import io.scos.connector.notion.internal.NotionTsRuns.PageView;
import io.scos.connector.notion.internal.NotionTsRuns.TsRun;
import io.scos.spi.connector.CallFailure;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * F6 동등성(TESTING §3.6 3층): 가짜 Notion(harness/fixtures/notion)에서 Java NotionAdapter가 roots 모드(운영 설정,
 * 체크포인트 v3 작업 큐)로 낸 페이지·변경·체크포인트·거부·원본·요청이 TS NotionAdapter와 같다. 페이지 중간 중단 뒤 재개도 본다.
 */
@DisplayName("Notion 어댑터는 roots 모드에서 TS NotionAdapter와 같은 페이지를 낸다(F6)")
class NotionAdapterFixtureTest {
  /** root-retro 첫 조회의 Retry-After(초). */
  static final Duration RETRO_RETRY_AFTER = Duration.ofSeconds(1);

  /** TS 장애 run(expected-failures.json): 이 경로의 blocks 조회가 응답하지 않는다. */
  static final String BLOCKS_TIMEOUT = "blocks 조회 시간 초과";

  static final String HANGING_PATH = "/v1/blocks/blk-wiki-toggle/children";

  final NotionScenario notion = new NotionScenario();

  @AfterEach
  void tearDown() {
    notion.close();
  }

  @Nested
  @DisplayName("roots 모드로 처음부터 수집하면 TS와 같다")
  class FirstRootsCollection {
    final TsRun ts = TsRun.firstRoots();

    @Test
    @DisplayName("페이지마다 같은 변경을 같은 순서로 낸다")
    void pagesCarrySameMutations() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::mutations)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::mutations).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 v3 작업 큐 체크포인트를 낸다")
    void pagesCarrySameCheckpoints() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::nextCheckpoint)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::nextCheckpoint).toList());
    }

    @Test
    @DisplayName("페이지마다 같은 거부를 낸다")
    void pagesCarrySameRejections() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.pageViews())
          .extracting(PageView::rejections)
          .containsExactlyElementsOf(ts.pages().stream().map(PageView::rejections).toList());
    }

    @Test
    @DisplayName("원본 항목의 필드가 같다(버전·순서·시각·메타데이터·본문 바이트·blob)")
    void itemsMatch() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.lastItems()).containsExactlyInAnyOrderElementsOf(ts.items());
    }

    @Test
    @DisplayName("같은 요청을 같은 순서로 보낸다")
    void sendsSameRequests() {
      // when
      notion.collect(Config.ROOTS);

      // then
      assertThat(notion.requests()).containsExactlyElementsOf(ts.requests());
    }

    @Test
    @DisplayName("사이클이 끝나면 체크포인트는 초기 시드로 돌아간다")
    void endsAtInitialSeed() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.pageViews().getLast().nextCheckpoint())
          .isEqualTo(ts.checkpoint().orElseThrow());
    }

    @Test
    @DisplayName("페이지 조회 요청 제한은 Retry-After만큼 기다린다")
    void waitsRetryAfter() {
      // when
      notion.collect(Config.ROOTS);

      // then
      assertThat(notion.waits()).containsExactly(RETRO_RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("행 둘째 페이지에서 중단되면")
  class Crashed {
    final JsonNode resume = NotionTsRuns.resumeFile();
    final TsRun ts = TsRun.of(resume.get("firstRun"));

    @BeforeEach
    void givenCrashAtSecondRowPage() {
      notion.crashAt(resume.get("crashAt"));
    }

    @Test
    @DisplayName("중단 전까지 TS와 같은 페이지를 낸다")
    void pagesBeforeCrashMatchTs() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }

    @Test
    @DisplayName("authentication_failed로 실패한다")
    void failsAsAuthenticationFailed() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.AUTHENTICATION_FAILED);
    }
  }

  @Nested
  @DisplayName("중단된 run의 체크포인트에서 재개하면 TS와 같다")
  class Resumed {
    final JsonNode resume = NotionTsRuns.resumeFile();
    final CheckpointView crashCheckpoint =
        CheckpointView.expected(resume.get("firstRun").get("checkpoint").get("checkpoint"));
    final TsRun ts = TsRun.of(resume.get("secondRun"));

    @Test
    @DisplayName("남은 작업부터 같은 페이지를 낸다")
    void pagesMatchTs() {
      // when
      Collection java = notion.collectFrom(Config.ROOTS, crashCheckpoint.checkpoint());

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }

    @Test
    @DisplayName("이미 확정된 작업은 다시 요청하지 않는다")
    void sendsOnlyRemainingRequests() {
      // when
      notion.collectFrom(Config.ROOTS, crashCheckpoint.checkpoint());

      // then
      assertThat(notion.requests()).containsExactlyElementsOf(ts.requests());
    }
  }

  @Nested
  @DisplayName("블록·행이 바뀐 뒤 초기 시드에서 다시 수집하면")
  class AfterEdit {
    final TsRun ts = TsRun.of(NotionTsRuns.rootsFile().get("secondRun"));

    @Test
    @DisplayName("TS와 같은 페이지를 낸다")
    void pagesMatchTs() {
      // given
      notion.phase(FakeNotionServer.Phase.EDITED);

      // when
      Collection java =
          notion.collectFrom(
              Config.ROOTS, TsRun.firstRoots().checkpoint().orElseThrow().checkpoint());

      // then
      assertThat(java.pageViews()).containsExactlyElementsOf(ts.pages());
    }
  }

  /** 헤더(Bearer)에 실을 수 없는 자격 증명 — CRLF .env·여러 줄 파일에서 온 값. 두 모드 모두 공급자를 부르기 전에 거른다. */
  @Nested
  @DisplayName("자격 증명 값에 줄바꿈이 있으면")
  class CredentialWithLineBreak {
    @BeforeEach
    void givenCredentialWithLineBreak() {
      notion.credentialResolvesTo(notion.token() + "\r\n");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = Config.class,
        names = {"ROOTS", "SEARCH"})
    @DisplayName("invalid_configuration으로 실패한다")
    void failsAsInvalidConfiguration(Config config) {
      // when
      Collection java = notion.collect(config);

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.INVALID_CONFIGURATION);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = Config.class,
        names = {"ROOTS", "SEARCH"})
    @DisplayName("공급자를 부르지 않는다")
    void sendsNoRequest(Config config) {
      // when
      notion.collect(config).requireFailed();

      // then
      assertThat(notion.requests()).isEmpty();
    }

    @Test
    @DisplayName("run errorKind는 자격 증명 실패의 Error로 남는다(ADR-0022 의도한 차이)")
    void storesTsErrorKind() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.errorKind()).contains("Error");
    }
  }

  @Nested
  @DisplayName("시작 전에 취소되면")
  class CancelledBeforeStart {
    @BeforeEach
    void givenCancelledBeforeStart() {
      notion.cancelBeforeStart();
    }

    @Test
    @DisplayName("cancelled로 실패한다")
    void failsAsCancelled() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.CANCELLED);
    }

    @Test
    @DisplayName("공급자를 부르지 않는다")
    void sendsNoRequest() {
      // when
      notion.collect(Config.ROOTS).requireFailed();

      // then
      assertThat(notion.requests()).isEmpty();
    }
  }

  /** 운영 Notion run 실패 1위(blocks 조회 시간 초과, F6-report §2)와 데몬 stop이 겹치는 경우. */
  @Nested
  @DisplayName("blocks 조회가 응답 없이 걸린 동안 취소되면")
  class CancelledWhileHanging {
    @BeforeEach
    void givenCancelledWhileBlocksHang() {
      notion.failureCase(NotionScenario.tsFailure(BLOCKS_TIMEOUT));
      notion.cancelWhenHanging();
    }

    @Test
    @DisplayName("cancelled로 실패한다")
    void failsAsCancelled() {
      // when
      Collection java = notion.collect(Config.ROOTS);

      // then
      assertThat(java.failureKind()).contains(CallFailure.Kind.CANCELLED);
    }

    @Test
    @DisplayName("걸린 요청을 재시도하지 않는다(요청 1회, 재시도 대기 없음)")
    void isNotRetried() {
      // when
      notion.collect(Config.ROOTS);

      // then
      assertThat(notion.requests().stream().filter(r -> r.path().equals(HANGING_PATH)))
          .as("걸린 경로의 요청")
          .hasSize(1);
      assertThat(notion.waits())
          .as("걸리기 전 root-retro 요청 제한의 대기뿐")
          .containsExactly(RETRO_RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("blocks 조회가 응답 없이 걸린 동안 수집 스레드가 인터럽트되면")
  class InterruptedWhileHanging {
    @BeforeEach
    void givenBlocksHang() {
      notion.failureCase(NotionScenario.tsFailure(BLOCKS_TIMEOUT));
    }

    @Test
    @DisplayName("cancelled로 실패한다")
    void failsAsCancelled() {
      // when
      NotionScenario.Interrupted run = notion.collectInterruptedWhileHanging(Config.ROOTS);

      // then
      assertThat(run.collection().failureKind()).contains(CallFailure.Kind.CANCELLED);
    }

    @Test
    @DisplayName("인터럽트 표시를 지우지 않는다")
    void keepsInterruptFlag() {
      // when
      NotionScenario.Interrupted run = notion.collectInterruptedWhileHanging(Config.ROOTS);

      // then
      assertThat(run.stillInterrupted()).isTrue();
    }
  }
}
