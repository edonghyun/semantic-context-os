package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.collect.CollectLock;
import io.scos.client.collect.StateStore;
import io.scos.client.source.transcript.TranscriptAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("로컬 stdio는 수집과 독립적으로 MCP 요청에 응답한다(D2, D6, D8)")
class ClientMainStdioTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path root;
  private ScosLocalScenario scenario;
  private Map<String, String> env;
  private Path home;

  @BeforeEach
  void writeSyntheticProject() throws IOException {
    scenario = new ScosLocalScenario(root);
    scenario.givenSession();
    home = scenario.state().getParent();
    env = scenario.environment();
  }

  @AfterEach
  void closeScenario() {
    scenario.close();
  }

  private static String[] args(String... values) {
    return values;
  }

  /** search_context 응답 본문(와이어 JSON)의 첫 hit. */
  private static JsonNode firstHit(JsonNode response) {
    String text = response.path("result").path("content").path(0).path("text").asString("");
    return text.isEmpty() ? JSON.missingNode() : JSON.readTree(text).path("hits").path(0);
  }

  /** search_context 응답의 hit 수. 응답이 없거나 형식이 다르면 -1. */
  private static int hitCount(JsonNode response) {
    String text = response.path("result").path("content").path(0).path("text").asString("");
    JsonNode hits = text.isEmpty() ? JSON.missingNode() : JSON.readTree(text).path("hits");
    return hits.isArray() ? hits.size() : -1;
  }

  private static boolean isJsonObject(String line) {
    try {
      return JSON.readTree(line).isObject();
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Nested
  @DisplayName("수집이 한 번 끝난 세션")
  class AfterCollection {
    private ScosStdioSession session;

    @BeforeEach
    void start() throws InterruptedException {
      session = scenario.startCollectedStdio();
    }

    @AfterEach
    void stop() throws Exception {
      session.close();
    }

    @Test
    @DisplayName("stdio initialize는 scos-local 이름을 준다")
    void initializeAnswersAsScosLocal() throws InterruptedException {
      // given: 기동 때 보낸 initialize

      // when
      JsonNode result = session.initialization();

      // then
      assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("scos-local");
    }

    @Test
    @DisplayName("stdio initialize는 로컬 작업 기록 안내문을 준다")
    void initializesWithLocalInstructions() throws InterruptedException {
      // given: 기동 때 보낸 initialize

      // when
      JsonNode result = session.initialization();

      // then
      assertThat(result.path("instructions").asString())
          .isEqualTo(
              """
          scos-local은 이 기기에서 나눈 Claude Code 세션 대화와 자동 메모리를 모은 내 작업 기록 검색입니다 (my own work history on this machine: past session turns and auto-memory, all projects).
          언제 쓰나: "어디까지 했지", "그때 왜 그렇게 했지", 지난 세션에서 정한 결정·이유·남은 일을 물을 때, 그리고 작업 중 이 저장소의 규칙·이름이 왜 그런지 궁금할 때 추측하기 전에 먼저 찾으세요. 사내 Slack·Notion·Jira 지식은 여기 없습니다(그건 scos).
          어떻게: 배경은 assemble_context 한 번부터, 후보를 훑으려면 search_context, 세션 전체는 read_document. 결과의 title에 프로젝트, 출처에 세션·날짜가 있으니 인용할 때 함께 적으세요.\
          """);
    }

    @Test
    @DisplayName("수집한 세션 검색 결과의 출처는 transcript다")
    void searchHitsCarryTranscriptSource() throws InterruptedException {
      // given: 첫 수집이 합성 세션을 색인했다

      // when
      JsonNode hit = firstHit(session.search());

      // then: SourceKind가 claude-transcript:// 스킴에 주는 와이어 값
      assertThat(hit.path("documentId").asString()).isEqualTo("transcript/d/s1");
      assertThat(hit.path("source").asString()).isEqualTo("transcript");
    }

    @Test
    @DisplayName("stdout에는 JSON-RPC 줄만 나온다")
    void stdoutIsProtocolOnly() throws Exception {
      // given
      session.sendSearch();

      // when
      int code = session.finish();

      // then
      assertThat(code).isZero();
      assertThat(session.lines()).hasSize(2).allMatch(ClientMainStdioTest::isJsonObject);
    }
  }

  @Nested
  @DisplayName("수집이 막혀 있는 세션")
  class WhileCollecting {
    private ScosStdioSession session;

    @BeforeEach
    void start() {
      session = scenario.startBlockedStdio();
    }

    @AfterEach
    void stop() throws Exception {
      session.close();
    }

    @Test
    @DisplayName("수집이 끝나기 전에도 tools/call에 답한다")
    void answersBeforeCollectionFinishes() throws InterruptedException {
      // given
      // when
      JsonNode response = session.searchWhileBlocked();

      // then
      assertThat(response.path("result").path("isError").asBoolean(false)).isFalse();
      assertThat(response.path("result").path("content").isArray()).isTrue();
      assertThat(session.collectionFinished()).isFalse();
    }
  }

  @Nested
  @DisplayName("다른 프로세스가 잠금을 쥔 세션")
  class LockHeldElsewhere {
    private CollectLock held;
    private ScosStdioSession session;

    @BeforeEach
    void start() {
      held = CollectLock.tryAcquire(home.resolve("collect.lock")).orElseThrow();
      session = scenario.startReadOnlyStdio();
    }

    @AfterEach
    void stop() throws Exception {
      try {
        session.close();
      } finally {
        held.close();
      }
    }

    @Test
    @DisplayName("다른 프로세스가 잠금을 쥐면 수집하지 않고 검색만 한다")
    void readOnlyWithoutLock() throws InterruptedException {
      // given
      // when
      JsonNode response = session.search();

      // then
      assertThat(session.collections()).isZero();
      assertThat(home.resolve("state.sqlite")).doesNotExist();
      assertThat(hitCount(response)).isZero();
    }
  }

  @Test
  @DisplayName("알 수 없는 인자는 종료 코드 2다")
  void rejectsUnknownArgument() {
    // given
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // when
    int code = ClientMain.run(args("bogus"), new ByteArrayInputStream(new byte[0]), out, env);

    // then
    assertThat(code).isEqualTo(2);
    assertThat(out.size()).isZero();
  }

  @Test
  @DisplayName("sync는 한 번 수집하고 stdout에 아무것도 쓰지 않는다")
  void syncCollectsOnce() {
    // given
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // when
    int code = ClientMain.run(args("sync"), new ByteArrayInputStream(new byte[0]), out, env);

    // then
    assertThat(code).isZero();
    assertThat(out.size()).isZero();
    assertThat(home.resolve("state.sqlite")).exists();
  }

  @Test
  @DisplayName("sync는 잠금을 못 잡으면 종료 코드 3이다")
  void syncFailsWhenLockHeld() {
    // given
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (CollectLock held = CollectLock.tryAcquire(home.resolve("collect.lock")).orElseThrow()) {
      // when
      int code = ClientMain.run(args("sync"), new ByteArrayInputStream(new byte[0]), out, env);

      // then
      assertThat(code).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("색인을 끝내 열지 못하면 예외로 죽지 않고 종료 코드 4다")
  void exitsWithCodeWhenIndexCannotOpen() throws IOException {
    // given
    Path notADirectory = Files.writeString(root.resolve("home-is-a-file"), "x");
    Map<String, String> broken = new HashMap<>(env);
    broken.put("SCOS_HOME", notADirectory.toString());
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // when
    int code = ClientMain.run(args(), new ByteArrayInputStream(new byte[0]), out, broken);

    // then
    assertThat(code).isEqualTo(4);
    assertThat(out.size()).isZero();
  }

  @Test
  @DisplayName("읽을 수 없는 색인이어도 state 수집은 하고 종료 코드 4다")
  void collectsIntoStateWhenIndexIsUnreadable() throws IOException {
    // given: 데이터베이스가 아닌 index.sqlite
    Files.createDirectories(home);
    Files.writeString(home.resolve("index.sqlite"), "이것은 sqlite 파일이 아니다".repeat(100));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // when
    int code = ClientMain.run(args("sync"), new ByteArrayInputStream(new byte[0]), out, env);

    // then
    assertThat(code).isEqualTo(4);
    try (StateStore state = StateStore.open(home.resolve("state.sqlite"))) {
      assertThat(state.documentIds(TranscriptAdapter.KIND)).containsExactly("transcript/d/s1");
    }
  }

  @Test
  @DisplayName("색인이 더 새 스키마여도 state 수집은 하고 종료 코드 4다")
  void collectsIntoStateWhenIndexIsNewer() throws Exception {
    // given: 이 클라이언트보다 새 버전이 만든 index.sqlite
    Files.createDirectories(home);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + home.resolve("index.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = 9999");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // when
    int code = ClientMain.run(args("sync"), new ByteArrayInputStream(new byte[0]), out, env);

    // then
    assertThat(code).isEqualTo(4);
    try (StateStore state = StateStore.open(home.resolve("state.sqlite"))) {
      assertThat(state.documentIds(TranscriptAdapter.KIND)).containsExactly("transcript/d/s1");
    }
  }

  @Test
  @DisplayName("SCOS_HOME이 상대 경로면 종료 코드 2다")
  void rejectsRelativeHome() {
    // given
    Map<String, String> relative = new HashMap<>(env);
    relative.put("SCOS_HOME", "relative/scos");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // when
    int code = ClientMain.run(args("sync"), new ByteArrayInputStream(new byte[0]), out, relative);

    // then
    assertThat(code).isEqualTo(2);
    assertThat(out.size()).isZero();
  }
}
