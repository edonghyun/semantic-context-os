package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.WorkClaim;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryUnitStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.ObjectMapper;

/**
 * 조회 명령의 인자 해석(TS commander 옵션)과 로그 경고 추출. 출력 바이트는 TS 비교({@link QueryCommandsTsEquivalenceTest})가,
 * 조회 의미는 PostgreSQL 테스트가 본다 — 여기서는 저장소에 넘기는 값만 기록하는 대역을 쓴다.
 */
@DisplayName("조회 명령은 TS commander와 같은 인자를 저장소 질의로 넘긴다")
class QueryCommandsTest {
  static final Instant NOW = Instant.parse("2026-10-16T01:02:03.456Z");

  /** 받은 limit·질의만 기록하고 빈 결과를 돌려준다. */
  static final class RecordingQueries implements SourceQueries {
    final List<Integer> rejectionLimits = new ArrayList<>();
    final List<RawCatalogQuery> rawQueries = new ArrayList<>();

    @Override
    public List<SourceInstance> listSources() {
      return List.of();
    }

    @Override
    public Optional<SourceStatus> status(SourceInstanceId source, Instant now) {
      return Optional.empty();
    }

    @Override
    public List<RecentRejection> recentRejections(SourceInstanceId source, int limit) {
      rejectionLimits.add(limit);
      return List.of();
    }

    @Override
    public RawCatalogPage rawList(RawCatalogQuery query) {
      rawQueries.add(query);
      return new RawCatalogPage(List.of(), Optional.empty());
    }

    @Override
    public Optional<RawEntry> rawGet(SourceInstanceId source, String externalId) {
      return Optional.empty();
    }

    /** 저장된 run(최근 것부터). recentRuns는 limit만큼 자른다 — 저장소 의미는 SourceStatusQueryTest가 본다. */
    List<CollectionRun> runs = List.of();

    @Override
    public List<CollectionRun> recentRuns(int limit) {
      return runs.subList(0, Math.min(limit, runs.size()));
    }

    @Override
    public List<WorkClaim> claims() {
      return List.of();
    }
  }

  final RecordingQueries queries = new RecordingQueries();
  final List<String> outputs = new ArrayList<>();
  final MutableClock clock = new MutableClock(NOW);
  final QueryCommands commands =
      new QueryCommands(
          queries,
          SourceAdapters.of(List.of()),
          new FreshnessReader(new MemoryUnitStore(), clock),
          Set.of(SpaceId.LEGACY),
          clock,
          new SourceQueryJson(new ObjectMapper()),
          outputs::add);

  static DefaultApplicationArguments args(String... args) {
    return new DefaultApplicationArguments(args);
  }

  /** 성공한 run n건(최근 것부터, jira-main). */
  static List<CollectionRun> givenRuns(int n) {
    List<CollectionRun> runs = new ArrayList<>();
    for (int i = n; i >= 1; i--)
      runs.add(
          new CollectionRun(
              new RunId("run-" + i),
              new SourceInstanceId("jira-main"),
              RunStatus.SUCCEEDED,
              NOW.minusSeconds(i),
              Optional.of(NOW),
              0,
              0,
              0,
              0,
              0,
              0,
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    return runs;
  }

  @Nested
  @DisplayName("source rejections를 부르면")
  class RejectionLimit {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"--limit=5, 5", "--limit=200, 200", "--limit=201, 200", "--limit=100000, 200"})
    @DisplayName("--limit은 200까지 그대로이고 넘으면 200으로 줄인다")
    void clamps(String option, int expected) {
      // when
      commands.source("rejections", args("source", "rejections", "jira-main", option));

      // then
      assertThat(queries.rejectionLimits).containsExactly(expected);
    }

    @Test
    @DisplayName("--limit이 없으면 20이다")
    void defaultsTo20() {
      // when
      commands.source("rejections", args("source", "rejections", "jira-main"));

      // then
      assertThat(queries.rejectionLimits).containsExactly(20);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"--limit=0", "--limit=-1", "--limit=abc"})
    @DisplayName("--limit이 양의 정수가 아니면 거부한다")
    void rejectsNonPositive(String option) {
      // when·then
      assertThatThrownBy(
              () -> commands.source("rejections", args("source", "rejections", "x", option)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--limit must be a positive integer");
    }
  }

  @Test
  @DisplayName("raw list의 --limit은 없으면 100이다")
  void rawListDefaultLimit() {
    // when
    commands.raw("list", args("raw", "list"));

    // then
    assertThat(queries.rawQueries).extracting(q -> q.limit()).containsExactly(100);
  }

  @Test
  @DisplayName("raw list의 --state는 active·deleted만 받는다")
  void rawListStateChoices() {
    // when·then
    assertThatThrownBy(() -> commands.raw("list", args("raw", "list", "--state=gone")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("argument 'gone' is invalid. Allowed choices are active, deleted.");
  }

  @Test
  @DisplayName("source status는 소스 id가 없으면 그 명령 모양이 든 사용법과 함께 거부한다")
  void statusNeedsSource() {
    // when·then: 사용법 전문은 명령이 늘 때마다 바뀌는 안내라 계약이 아니다 — 머리말과 이 명령의 모양만 본다
    assertThatThrownBy(() -> commands.source("status", args("source", "status")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("usage: ")
        .hasMessageContaining("| source status <id> |");
  }

  @Nested
  @DisplayName("status가 로그 경고를 뽑을 때")
  class LogProblems {
    @TempDir Path dir;

    @Test
    @DisplayName("warn·error 줄 중 마지막 세 줄을 싣는다")
    void lastThreeProblems() throws IOException {
      // given
      Path log =
          Files.writeString(
              dir.resolve("serve.log"),
              """
              {"level":"warn","event":"a"}
              {"level":"info","event":"b"}
              {"level":"error","event":"c"}
              {"level":"warn","event":"d"}
              {"level":"error","event":"e"}
              {"level":"info","event":"f"}
              """);

      // when
      var problems = QueryCommands.logProblems(log.toString());

      // then
      assertThat(problems.lines())
          .containsExactly(
              "{\"level\":\"error\",\"event\":\"c\"}",
              "{\"level\":\"warn\",\"event\":\"d\"}",
              "{\"level\":\"error\",\"event\":\"e\"}");
    }

    @Test
    @DisplayName("파일이 없으면 실패하지 않고 found=false다")
    void missingFile() {
      // when
      var problems = QueryCommands.logProblems(dir.resolve("absent.log").toString());

      // then
      assertThat(problems.found()).isFalse();
    }
  }

  @Test
  @DisplayName("status의 최근 run은 6건까지다")
  void overviewKeepsSixRuns() {
    // given
    queries.runs = givenRuns(7);

    // when
    commands.overview(args("status"));

    // then
    assertThat(new ObjectMapper().readTree(outputs.getFirst()).get("recentRuns").size())
        .isEqualTo(6);
  }
}
