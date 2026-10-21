package io.scos;

import static io.scos.CommandScenario.PUBLIC_CHANNELS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.SourceSchedule;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.connector.slack.SlackAssembly;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 제품 조립 스모크(실제 Spring 컨텍스트 + Testcontainers + 가짜 Slack): `source add` → `collect` → `project` →
 * `cycle`·`maintenance` 명령이 조립된 빈으로 끝까지 돈다. 실제 외부 호출 0. 명령 묶음은 상황마다 @BeforeAll에서 한 번 실행하고 기록을 계약별
 * 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@DisplayName("운영 명령은 조립된 제품 빈으로 끝까지 돈다(실제 외부 호출 0)")
class CommandsSmokeTest {
  static final String GOOD = "slack-synthetic";
  static final int SUCCESS = 0;
  static final int CYCLE_HAD_FAILURES = 1;
  static final int COLLECTED_ITEMS = 9;
  static final int PROJECTED_UNITS = 3;

  abstract static class Situation {
    CommandScenario commands;

    /** 뒤 명령의 전제인 명령의 종료 코드. 0이 아니면 뒤 명령을 돌리지 않고 멈춘다(TESTING §2.5 규칙 6 조건 1). */
    static int requireSuccess(String command, int exit) {
      if (exit != SUCCESS)
        throw new IllegalStateException("given 준비 실패: " + command + " 종료 코드가 0이 아니다: " + exit);
      return exit;
    }

    @AfterAll
    void close() {
      if (commands != null) commands.close();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("source add → collect → project → cycle을 차례로 실행하면")
  class EndToEnd extends Situation {
    List<Integer> exits;
    int sourcesAfterAdd;
    long itemsAfterCollect;
    int requestsAfterCollect;
    int unitsAfterProject;
    int requestsAfterProject;
    int unitsAfterCycle;
    long itemsAfterCycle;

    @BeforeAll
    void runCommands() {
      commands = new CommandScenario();
      int add = requireSuccess("source add", commands.addSource(GOOD, PUBLIC_CHANNELS));
      sourcesAfterAdd = commands.enabledSources();
      int collect = requireSuccess("collect", commands.run("collect", "--source=" + GOOD));
      itemsAfterCollect = commands.rawItems(GOOD);
      requestsAfterCollect = commands.pendingRequests();
      int project = requireSuccess("project", commands.run("project"));
      unitsAfterProject = commands.units();
      requestsAfterProject = commands.pendingRequests();
      int cycle = commands.run("cycle");
      unitsAfterCycle = commands.units();
      itemsAfterCycle = commands.rawItems(GOOD);
      exits = List.of(add, collect, project, cycle);
    }

    @Test
    @DisplayName("네 명령 모두 종료 코드 0이다")
    void allSucceed() {
      // then
      assertThat(exits).containsOnly(SUCCESS);
    }

    @Test
    @DisplayName("source add는 켜진 소스 하나를 등록한다")
    void registersSource() {
      // then
      assertThat(sourcesAfterAdd).isEqualTo(1);
    }

    @Test
    @DisplayName("collect는 원본 9건을 남긴다")
    void collects() {
      // then
      assertThat(itemsAfterCollect).isEqualTo(COLLECTED_ITEMS);
    }

    @Test
    @DisplayName("collect는 투영 요청 하나를 남긴다")
    void collectRequestsProjection() {
      // then
      assertThat(requestsAfterCollect).isEqualTo(1);
    }

    @Test
    @DisplayName("project는 유닛 3건을 만든다")
    void projects() {
      // then
      assertThat(unitsAfterProject).isEqualTo(PROJECTED_UNITS);
    }

    @Test
    @DisplayName("project 뒤 투영 요청은 남지 않는다")
    void projectClearsRequests() {
      // then
      assertThat(requestsAfterProject).isZero();
    }

    @Test
    @DisplayName("cycle은 다시 돌아도 유닛을 중복해 만들지 않는다")
    void cycleAddsNoDuplicates() {
      // then
      assertThat(unitsAfterCycle).isEqualTo(PROJECTED_UNITS);
    }

    @Test
    @DisplayName("cycle은 다시 돌아도 원본을 중복해 넣지 않는다")
    void cycleAddsNoDuplicateItems() {
      // then
      assertThat(itemsAfterCycle).isEqualTo(COLLECTED_ITEMS);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("cycle에서 한 소스의 수집이 실패(invalid_configuration)하면")
  class CycleWithFailingSource extends Situation {
    int cycleExit;
    Optional<String> badErrorKind;
    long goodItems;
    int units;
    int projectExit;

    @BeforeAll
    void runCycle() {
      commands = new CommandScenario();
      requireSuccess(
          "source add slack-bad",
          commands.addSource(
              "slack-bad",
              "{\"conversationTypes\":[\"public_channel\"],\"channelIds\":[\"CMISSING\"]}"));
      requireSuccess("source add", commands.addSource(GOOD, PUBLIC_CHANNELS));
      cycleExit = commands.run("cycle");
      badErrorKind = commands.latestErrorKind("slack-bad");
      goodItems = commands.rawItems(GOOD);
      units = commands.units();
      projectExit = commands.run("project");
    }

    @Test
    @DisplayName("종료 코드는 1이다")
    void exitsWithFailure() {
      // then
      assertThat(cycleExit).isEqualTo(CYCLE_HAD_FAILURES);
    }

    @Test
    @DisplayName("실패한 소스의 run은 그 오류 종류를 남긴다")
    void recordsFailure() {
      // then
      assertThat(badErrorKind).contains("invalid_configuration");
    }

    @Test
    @DisplayName("다른 소스는 수집된다")
    void otherSourceIsCollected() {
      // then
      assertThat(goodItems).isEqualTo(COLLECTED_ITEMS);
    }

    @Test
    @DisplayName("다른 소스는 투영된다")
    void otherSourceIsProjected() {
      // then
      assertThat(units).isEqualTo(PROJECTED_UNITS);
    }

    @Test
    @DisplayName("그 뒤 project는 0으로 끝난다")
    void nothingLeftToProject() {
      // then
      assertThat(projectExit).isEqualTo(SUCCESS);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("cycle에서 앞 소스의 수집이 예외(설정 드리프트)로 끝나면")
  class CycleWithThrowingSource extends Situation {
    int cycleExit;
    boolean driftedHasRun;
    long goodItems;
    int units;

    @BeforeAll
    void runCycle() {
      commands = new CommandScenario();
      commands.givenDriftedSource("a-drifted");
      requireSuccess("source add", commands.addSource(GOOD, PUBLIC_CHANNELS));
      cycleExit = commands.run("cycle");
      driftedHasRun = commands.hasRun("a-drifted");
      goodItems = commands.rawItems(GOOD);
      units = commands.units();
    }

    @Test
    @DisplayName("종료 코드는 1이다")
    void exitsWithFailure() {
      // then
      assertThat(cycleExit).isEqualTo(CYCLE_HAD_FAILURES);
    }

    @Test
    @DisplayName("예외 경로라 앞 소스의 run은 없다")
    void noRunForThrowingSource() {
      // then
      assertThat(driftedHasRun).isFalse();
    }

    @Test
    @DisplayName("뒤 소스는 수집된다")
    void laterSourceIsCollected() {
      // then
      assertThat(goodItems).isEqualTo(COLLECTED_ITEMS);
    }

    @Test
    @DisplayName("뒤 소스는 투영된다")
    void laterSourceIsProjected() {
      // then
      assertThat(units).isEqualTo(PROJECTED_UNITS);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("고아 blob이 기본 유예(30일) 안팎에 있을 때")
  class MaintenanceGrace extends Situation {
    static final Duration BEYOND_GRACE = Duration.ofDays(31);
    static final Duration WITHIN_GRACE = Duration.ofDays(29);

    int dryRunExit;
    List<String> afterDryRun;
    int maintenanceExit;
    List<String> afterMaintenance;

    @BeforeAll
    void runMaintenance() {
      commands = new CommandScenario();
      commands.givenOrphanBlob("old-orphan", BEYOND_GRACE);
      commands.givenOrphanBlob("young-orphan", WITHIN_GRACE);
      dryRunExit = commands.run("maintenance", "--dry-run");
      afterDryRun = commands.blobShas();
      maintenanceExit = commands.run("maintenance");
      afterMaintenance = commands.blobShas();
    }

    @Test
    @DisplayName("maintenance --dry-run은 0으로 끝난다")
    void dryRunSucceeds() {
      // then
      assertThat(dryRunExit).isEqualTo(SUCCESS);
    }

    @Test
    @DisplayName("maintenance --dry-run은 아무것도 지우지 않는다")
    void dryRunDeletesNothing() {
      // then
      assertThat(afterDryRun).containsExactly("old-orphan", "young-orphan");
    }

    @Test
    @DisplayName("옵션 없는 maintenance는 0으로 끝난다")
    void maintenanceSucceeds() {
      // then
      assertThat(maintenanceExit).isEqualTo(SUCCESS);
    }

    @Test
    @DisplayName("옵션 없는 maintenance는 유예 밖 blob만 지운다")
    void deletesOnlyBeyondGrace() {
      // then
      assertThat(afterMaintenance).containsExactly("young-orphan");
    }
  }

  @Nested
  @DisplayName("maintenance에 --grace-days를 주면")
  class GraceDays {
    /** 하루보다 어리다 — 0이 아닌 유예(1일 이상)로 잘못 읽으면 남는다. */
    static final Duration HALF_DAY = Duration.ofHours(12);

    static final Duration BEYOND_GRACE = Duration.ofDays(31);

    CommandScenario commands;

    @BeforeEach
    void assemble() {
      commands = new CommandScenario();
    }

    @AfterEach
    void close() {
      if (commands != null) commands.close();
    }

    @Test
    @DisplayName("0이면 0으로 끝나고, 유예 없이 모든 고아 blob을 지운다")
    void zeroDeletesAll() {
      // given
      commands.givenOrphanBlob("young-orphan", HALF_DAY);

      // when
      int exit = commands.run("maintenance", "--grace-days=0");

      // then
      assertThat(exit).isEqualTo(SUCCESS);
      assertThat(commands.blobShas()).isEmpty();
    }

    @ParameterizedTest(name = "--grace-days={0}")
    @ValueSource(strings = {"abc", "-1"})
    @DisplayName("음이 아닌 정수가 아니면 입력을 밝혀 실패한다")
    void rejectsInvalid(String raw) {
      // given
      commands.givenOrphanBlob("old-orphan", BEYOND_GRACE);

      // when·then
      assertThatThrownBy(() -> commands.run("maintenance", "--grace-days=" + raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("--grace-days must be a non-negative integer: " + raw);
    }

    @ParameterizedTest(name = "--grace-days={0}")
    @ValueSource(strings = {"abc", "-1"})
    @DisplayName("음이 아닌 정수가 아니면 거부되고, 아무것도 지우지 않는다")
    void invalidDeletesNothing(String raw) {
      // given
      commands.givenOrphanBlob("old-orphan", BEYOND_GRACE);

      // when
      Throwable rejected = catchThrowable(() -> commands.run("maintenance", "--grace-days=" + raw));

      // then
      assertThat(rejected).isInstanceOf(IllegalArgumentException.class);
      assertThat(commands.blobShas()).containsExactly("old-orphan");
    }
  }

  @Nested
  @DisplayName("source add에 kind와 TS 설정 파일을 주면")
  class SourceAddInput {
    static final String DM_WITHOUT_CHANNELS = "{\"conversationTypes\":[\"im\"]}";

    CommandScenario commands;

    @BeforeEach
    void assemble() {
      commands = new CommandScenario();
    }

    @AfterEach
    void close() {
      if (commands != null) commands.close();
    }

    int add(String kind, String configValue) {
      return commands.sourceAdd(
          "--id=" + GOOD,
          "--kind=" + kind,
          "--config=" + commands.configFile(configValue),
          "--credential-ref=" + SlackAssembly.CREDENTIAL_REF);
    }

    @Test
    @DisplayName("TS 설정 파일로 등록하면 그 스키마 버전과 값이 남는다")
    void registersFromTsConfigFile() {
      // when
      add("slack", PUBLIC_CHANNELS);

      // then
      assertThat(commands.config(GOOD)).isEqualTo(new SourceConfig(1, PUBLIC_CHANNELS));
    }

    static final String TIRO_FOLDER = "{\"workspaceGuid\":\"ws\",\"folderId\":\"folder\"}";

    @Test
    @DisplayName("Tiro TS 설정 파일로 등록하면 그 설정이 남는다")
    void registersTiroFromTsConfigFile() {
      // when
      add("tiro", TIRO_FOLDER);

      // then
      assertThat(commands.config(GOOD)).isEqualTo(new SourceConfig(1, TIRO_FOLDER));
    }

    /** TS 운영 등록과 같은 모양의 Jira 설정(사이트·Basic 인증 email·첨부 다운로드). */
    static final String JIRA_SITE =
        "{\"siteUrl\":\"https://synthetic.atlassian.net\",\"email\":\"ops@example.test\","
            + "\"downloadAttachments\":true}";

    @Test
    @DisplayName("Jira TS 설정 파일로 등록하면 그 설정이 남는다")
    void registersJiraFromTsConfigFile() {
      // when
      add("jira", JIRA_SITE);

      // then
      assertThat(commands.config(GOOD)).isEqualTo(new SourceConfig(1, JIRA_SITE));
    }

    /** TS 운영 등록 설정(notion-config.json — roots 모드). */
    static final String NOTION_ROOTS = "{\"rootPageIds\":[\"root-a\",\"root-b\",\"root-c\"]}";

    @Test
    @DisplayName("Notion TS 설정 파일로 등록하면 그 설정이 남는다")
    void registersNotionFromTsConfigFile() {
      // when
      add("notion", NOTION_ROOTS);

      // then
      assertThat(commands.config(GOOD)).isEqualTo(new SourceConfig(1, NOTION_ROOTS));
    }

    @Test
    @DisplayName("등록되지 않은 kind는 등록된 kind 목록을 담아 실패한다")
    void rejectsUnknownKind() {
      // when·then
      assertThatThrownBy(() -> add("unknown-kind", PUBLIC_CHANNELS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Source adapter not found: unknown-kind. Registered kinds: google-drive, jira, notion, slack, tiro");
    }

    @Test
    @DisplayName("어댑터의 설정 검증에 실패하면 invalid_configuration으로 실패한다")
    void rejectsInvalidConfig() {
      // when·then
      assertThatThrownBy(() -> add("slack", DM_WITHOUT_CHANNELS))
          .isInstanceOfSatisfying(
              CallFailureException.class,
              e -> assertThat(e.failure().kind()).isEqualTo(Kind.INVALID_CONFIGURATION));
    }

    @Test
    @DisplayName("어댑터의 설정 검증에 실패하면 거부되고, 소스를 등록하지 않는다")
    void invalidConfigRegistersNothing() {
      // when
      Throwable rejected = catchThrowable(() -> add("slack", DM_WITHOUT_CHANNELS));

      // then
      assertThat(rejected).isInstanceOf(CallFailureException.class);
      assertThat(commands.enabledSources()).isZero();
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("수집한 소스를 같은 id로 주기를 넣어 다시 source add하면(parallel-run §2.3)")
  class SourceReAdd extends Situation {
    static final Duration SIX_HOURS = Duration.ofHours(6);

    StoredCheckpoint checkpointBefore;
    int readdExit;
    int sourcesAfter;
    SourceSchedule scheduleAfter;
    StoredCheckpoint checkpointAfter;
    long itemsAfter;

    @BeforeAll
    void reAdd() {
      commands = new CommandScenario();
      requireSuccess("source add", commands.addSource(GOOD, PUBLIC_CHANNELS));
      requireSuccess("collect", commands.run("collect", "--source=" + GOOD));
      checkpointBefore = commands.checkpoint(GOOD);
      if (checkpointBefore.checkpoint().isEmpty() || commands.rawItems(GOOD) != COLLECTED_ITEMS)
        throw new IllegalStateException("given 준비 실패: 다시 등록 전 수집이 체크포인트·원본을 남기지 않았다");
      readdExit =
          commands.addSource(GOOD, PUBLIC_CHANNELS, "--interval-seconds=" + SIX_HOURS.toSeconds());
      sourcesAfter = commands.enabledSources();
      scheduleAfter = commands.schedule(GOOD);
      checkpointAfter = commands.checkpoint(GOOD);
      itemsAfter = commands.rawItems(GOOD);
    }

    @Test
    @DisplayName("종료 코드 0으로 끝난다")
    void succeeds() {
      // then
      assertThat(readdExit).isEqualTo(SUCCESS);
    }

    @Test
    @DisplayName("소스를 더하지 않고 하나로 둔다")
    void keepsOneSource() {
      // then
      assertThat(sourcesAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("등록을 새 주기로 덮어쓴다")
    void overwritesSchedule() {
      // then
      assertThat(scheduleAfter)
          .isEqualTo(new SourceSchedule(Optional.of(SIX_HOURS), Optional.empty()));
    }

    @Test
    @DisplayName("체크포인트는 그대로다")
    void keepsCheckpoint() {
      // then
      assertThat(checkpointAfter).isEqualTo(checkpointBefore);
    }

    @Test
    @DisplayName("원본은 그대로다")
    void keepsRawItems() {
      // then
      assertThat(itemsAfter).isEqualTo(COLLECTED_ITEMS);
    }
  }

  @Nested
  @DisplayName("source add로 소스를 등록할 때")
  class SourceSchedules {
    static final Duration SIX_HOURS = Duration.ofHours(6);
    static final Duration DAILY = Duration.ofDays(1);

    CommandScenario commands;

    @BeforeEach
    void assemble() {
      commands = new CommandScenario();
    }

    @AfterEach
    void close() {
      if (commands != null) commands.close();
    }

    @Test
    @DisplayName("주기를 주면 등록된 소스가 그 주기를 가진다")
    void storesGivenSchedule() {
      // when
      commands.addSource(
          GOOD,
          PUBLIC_CHANNELS,
          "--interval-seconds=" + SIX_HOURS.toSeconds(),
          "--snapshot-interval-seconds=" + DAILY.toSeconds());

      // then
      assertThat(commands.schedule(GOOD))
          .isEqualTo(new SourceSchedule(Optional.of(SIX_HOURS), Optional.of(DAILY)));
    }

    @Test
    @DisplayName("주기를 주지 않으면 데몬 기본값을 따른다")
    void defaultsToDaemon() {
      // when
      commands.addSource(GOOD, PUBLIC_CHANNELS);

      // then
      assertThat(commands.schedule(GOOD)).isEqualTo(SourceSchedule.DAEMON_DEFAULTS);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"--interval-seconds", "--snapshot-interval-seconds"})
    @DisplayName("값 없이 준 선택 옵션은 사용법과 함께 실패한다")
    void rejectsOptionWithoutValue(String option) {
      // when·then
      assertThatThrownBy(() -> commands.addSource(GOOD, PUBLIC_CHANNELS, option))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith(option + " needs a value.");
    }

    @ParameterizedTest(name = "--interval-seconds={0}")
    @ValueSource(strings = {"0", "abc", "1.5"})
    @DisplayName("양의 정수가 아니면 입력을 밝혀 실패한다")
    void rejectsInvalid(String raw) {
      // when·then
      assertThatThrownBy(
              () -> commands.addSource(GOOD, PUBLIC_CHANNELS, "--interval-seconds=" + raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("--interval-seconds must be a positive integer: " + raw);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("project 명령으로 제품을 조립하면")
  class DataSources extends Situation {
    String lockTimeout;
    DataSource appDataSource;
    DataSource flywayDataSource;

    @BeforeAll
    void inspect() {
      commands = new CommandScenario();
      commands.run(
          ctx -> {
            appDataSource = ctx.getBean(DataSource.class);
            lockTimeout =
                JdbcClient.create(appDataSource)
                    .sql("SHOW lock_timeout")
                    .query(String.class)
                    .single();
            flywayDataSource = ctx.getBean(Flyway.class).getConfiguration().getDataSource();
          },
          "project");
    }

    @Test
    @DisplayName("연결마다 lock_timeout 5s를 건다")
    void setsLockTimeout() {
      // then
      assertThat(lockTimeout).isEqualTo("5s");
    }

    @Test
    @DisplayName("Flyway는 별도 DataSource로 마이그레이션한다")
    void flywayUsesItsOwnDataSource() {
      // then
      assertThat(flywayDataSource).isNotSameAs(appDataSource);
    }
  }
}
