package io.scos.client.source.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.source.CollectedPages;
import io.scos.client.source.SourceAdapterScenario;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.SourceDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("트랜스크립트 투영기는 실제 메시지 블록을 세션 문서로 묶는다(CG-1.4)")
class TranscriptProjectorTest {

  @TempDir Path root;
  private final TranscriptProjector projector = new TranscriptProjector();
  private List<RawItem> items;

  @BeforeEach
  void setUp() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/transcripts/turns.jsonl")) {
      items = collectItems(in.readAllBytes());
    }
  }

  private List<RawItem> collectItems(byte[] transcript) throws IOException {
    Path dir = Files.createDirectories(root.resolve("projects/d"));
    Files.write(dir.resolve("s1.jsonl"), transcript);
    TranscriptAdapter adapter =
        new TranscriptAdapter(
            root.resolve("projects"),
            Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
    List<CollectionPage> pages =
        new SourceAdapterScenario(
                adapter, Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC))
            .collect(Optional.empty());
    return CollectedPages.items(pages);
  }

  private List<RawItem> givenSessionGroup() {
    var groups = projector.groupIntoDocuments(items);
    if (!groups.keySet().equals(Set.of("transcript/d/s1")))
      throw new IllegalStateException("given 준비 실패: 세션 s1의 투영 입력이 있어야 한다");
    return groups.get("transcript/d/s1");
  }

  private SourceDocument project() {
    return projector
        .project("src", "transcript/d/s1", givenSessionGroup(), CollectedPages::read)
        .orElseThrow();
  }

  @Test
  @DisplayName("세션의 원본 줄은 하나의 문서 식별자로 묶인다")
  void groupsSessionLines() {
    // when
    var groups = projector.groupIntoDocuments(items);
    // then
    assertThat(groups).containsOnlyKeys("transcript/d/s1");
  }

  @Test
  @DisplayName("사용자 동의 발화는 직전 어시스턴트 제안과 한 묶음이다")
  void bundlesAgreementWithPreviousProposal() {
    // when
    String body = project().body();

    // then
    assertThat(body)
        .contains("## 턴 2 · 2026-10-27T10:00:06Z")
        .containsSubsequence("## 턴 2 ·", "자동 메모리는 대체하지 않고 보완하자", "사용자: 대체는 별로", "## 턴 3 ·");
    assertThat(body).contains("## 턴 1 · 2026-10-27T10:00:01Z");
  }

  @Test
  @DisplayName("thinking과 도구 출력 원문은 본문에 없고 도구 호출은 한 줄 요약이다")
  void dropsThinkingAndToolOutput() {
    // when
    String body = project().body();

    // then
    assertThat(body).contains("- 도구 Bash: ./gradlew test");
    assertThat(body)
        .doesNotContain("합성 생각 문장")
        .doesNotContain("합성 도구 출력 문장")
        .doesNotContain("--info");
  }

  @Test
  @DisplayName("질문 도구의 질문과 답은 한 묶음이다")
  void bundlesAskUserQuestion() {
    // when
    String body = project().body();

    // then
    assertThat(body)
        .containsSubsequence("## 턴 3 ·", "하나 여쭤볼게요", "질문: 저장 위치?\n답: ~/.scos", "## 턴 4 ·")
        .doesNotContain("합성 답변 원문");
  }

  @Test
  @DisplayName("제목은 프로젝트 이름과 ai 제목이다")
  void titleFromCwdAndAiTitle() {
    // when
    SourceDocument doc = project();

    // then
    assertThat(doc.title()).isEqualTo("semantic-context-os · 검색 강도 정리");
  }

  @Test
  @DisplayName("주소는 폴더와 세션이다")
  void uri() {
    // when
    SourceDocument doc = project();

    // then
    assertThat(doc.uri()).isEqualTo("claude-transcript://d/s1");
  }

  @Test
  @DisplayName("시각은 첫 줄과 마지막 줄의 것이다")
  void times() {
    // when
    SourceDocument doc = project();

    // then
    assertThat(doc.createdAt()).isEqualTo("2026-10-27T10:00:01.123Z");
    assertThat(doc.updatedAt()).isEqualTo("2026-10-27T10:00:17.000Z");
  }

  @Test
  @DisplayName("첫 줄과 마지막 줄이 시각 없는 ai-title이면 시각 있는 가장 가까운 줄의 시각을 쓴다")
  void timesSkipLinesWithoutTimestamp() throws IOException {
    // given
    String transcript =
        String.join(
                "\n",
                TranscriptLines.aiTitle("첫 제목").build(),
                TranscriptLines.user("질문")
                    .timestamp("2026-10-27T10:00:01.123Z")
                    .cwd("/w/p")
                    .build(),
                TranscriptLines.assistant("답")
                    .timestamp("2026-10-27T10:00:09.000Z")
                    .cwd("/w/p")
                    .build(),
                TranscriptLines.aiTitle("마지막 제목").build())
            + "\n";
    items = collectItems(transcript.getBytes(StandardCharsets.UTF_8));

    // when
    SourceDocument doc = project();

    // then
    assertThat(doc.createdAt()).isEqualTo("2026-10-27T10:00:01.123Z");
    assertThat(doc.updatedAt()).isEqualTo("2026-10-27T10:00:09.000Z");
  }

  @Test
  @DisplayName("서브에이전트 알림과 압축 요약은 동의 발화를 제안에서 떼어 놓지 않는다")
  void notificationsDoNotSplitBundles() {
    // when
    String body = project().body();

    // then
    assertThat(body)
        .containsSubsequence("## 턴 4 ·", "제안: 이렇게 하자", "- 알림: 백그라운드 작업 끝", "사용자: ㅇㅋ", "## 턴 5 ·");
    assertThat(body).doesNotContain("합성 압축 요약 문장").doesNotContain("합성 메타 문장");
  }

  @Test
  @DisplayName("사용자 발화 없는 꼬리와 명령 줄은 마지막 묶음에 들어간다")
  void tailBundleHoldsCommands() {
    // when
    String body = project().body();

    // then
    assertThat(body).containsSubsequence("## 턴 5 ·", "마무리합니다", "- 명령: <command-name>/clear");
  }

  @Test
  @DisplayName("제목은 마지막 ai-title이다")
  void lastAiTitle() {
    // when
    SourceDocument doc = project();

    // then
    assertThat(doc.title()).isEqualTo("semantic-context-os · 검색 강도 정리");
  }

  @Test
  @DisplayName("같은 줄 묶음은 같은 문서를 낸다")
  void deterministic() {
    // when
    SourceDocument first = project();
    SourceDocument second = project();

    // then
    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("줄 번호는 숫자 순서로 정렬한다")
  void sortsNumerically() {
    // given
    items = items.reversed();
    // when
    var groups = projector.groupIntoDocuments(items);

    // then
    List<String> ids = groups.get("transcript/d/s1").stream().map(RawItem::externalId).toList();
    assertThat(ids.indexOf("d/s1#10")).isGreaterThan(ids.indexOf("d/s1#9"));
  }

  @Test
  @DisplayName("턴이 아닌 원본 줄은 세션의 투영 입력에서 버린다")
  void dropsNonTurnLine() {
    // given
    if (items.stream().noneMatch(i -> i.externalId().equals("d/s1#5")))
      throw new IllegalStateException("given 준비 실패: 투영하지 않는 원본 줄");
    // when
    var groups = projector.groupIntoDocuments(items);
    // then
    List<String> ids = groups.get("transcript/d/s1").stream().map(RawItem::externalId).toList();
    assertThat(ids).doesNotContain("d/s1#5");
  }

  @Test
  @DisplayName("투영에 역순 줄을 줘도 사용자 발화는 원래 순서로 묶인다")
  void projectsUnorderedLines() throws IOException {
    // given
    items =
        collectItems(
            (TranscriptLines.user("첫 질문").build()
                    + "\n"
                    + TranscriptLines.assistant("첫 제안").build()
                    + "\n"
                    + TranscriptLines.user("다음 질문").build()
                    + "\n")
                .getBytes(StandardCharsets.UTF_8));
    // when
    String body =
        projector
            .project("src", "transcript/d/s1", items.reversed(), CollectedPages::read)
            .orElseThrow()
            .body();
    // then
    assertThat(body)
        .isEqualTo(
            "## 턴 1 · 2026-10-28T00:00:00Z\n\n사용자: 첫 질문\n\n"
                + "## 턴 2 · 2026-10-28T00:00:00Z\n\n첫 제안\n\n사용자: 다음 질문");
  }

  @Test
  @DisplayName("빈 시각을 가진 줄은 문서의 첫 시각으로 쓰지 않는다")
  void skipsEmptyTimestamp() throws IOException {
    // given
    items =
        collectItems(
            (TranscriptLines.user("질문").timestamp("").build()
                    + "\n"
                    + TranscriptLines.assistant("답").build()
                    + "\n")
                .getBytes(StandardCharsets.UTF_8));
    // when
    SourceDocument doc = project();
    // then
    assertThat(doc.createdAt()).isEqualTo("2026-10-28T00:00:00Z");
  }

  @ParameterizedTest
  @MethodSource("toolTargets")
  @DisplayName("도구 대상은 파일·명령·패턴·주소 우선순위로 요약한다")
  void summarizesToolTarget(Map<String, String> input, String expected) throws IOException {
    // given
    String line = TranscriptLines.toolUse("Tool", input).build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n- 도구 Tool: " + expected);
  }

  @Test
  @DisplayName("도구 명령의 첫 줄이 비었으면 대상 없이 이름만 요약한다")
  void emptyCommandFirstLineHasNoTarget() throws IOException {
    // given
    String line = TranscriptLines.toolUse("Tool", Map.of("command", "\npwd")).build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n- 도구 Tool");
  }

  static List<Arguments> toolTargets() {
    return List.of(
        Arguments.of(Map.of("file_path", "src/a.java", "command", "ls"), "src/a.java"),
        Arguments.of(Map.of("command", "ls\npwd", "pattern", "*.java"), "ls"),
        Arguments.of(Map.of("pattern", "*.java", "url", "https://example.test"), "*.java"),
        Arguments.of(Map.of("url", "https://example.test"), "https://example.test"));
  }

  @Test
  @DisplayName("질문 호출이 없는 답도 답 항목의 질문과 함께 보존한다")
  void preservesOrphanAnswers() throws IOException {
    // given
    String line = TranscriptLines.toolResult("선택 결과").answers(Map.of("언어?", "Java")).build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n질문: 언어?\n답: Java");
  }

  @Test
  @DisplayName("문자열 형태의 어시스턴트 답도 본문에 넣는다")
  void preservesAssistantStringContent() throws IOException {
    // given
    String line =
        TranscriptLines.assistant("답")
            .with("message", Map.of("role", "assistant", "content", "답"))
            .build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n답");
  }

  @Test
  @DisplayName("파싱할 수 없는 시각은 원문을 보존한다")
  void preservesUnparseableTimestamp() throws IOException {
    // given
    String line = TranscriptLines.user("질문").timestamp("unknown-time").build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · unknown-time\n\n사용자: 질문");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("io.scos.client.source.transcript.TranscriptLineCases#nonProjected")
  @DisplayName("원문 보존용 줄은 투영 문서의 입력에서 제외한다")
  void excludesNonProjectedLines(String kind, String line) throws IOException {
    // given
    items =
        collectItems(
            (TranscriptLines.user("질문").build() + "\n" + line + "\n")
                .getBytes(StandardCharsets.UTF_8));

    // when
    var groups = projector.groupIntoDocuments(items);

    // then
    assertThat(groups.get("transcript/d/s1"))
        .extracting(RawItem::externalId)
        .containsExactly("d/s1#0");
  }

  @Test
  @DisplayName("문자열 형태의 사용자 발화도 본문에 넣는다")
  void userStringContent() throws IOException {
    // given
    String line =
        TranscriptLines.user("합성 질문")
            .with("message", Map.of("role", "user", "content", "합성 질문"))
            .build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    Optional<String> body =
        projector
            .project("src", "transcript/d/s1", items, CollectedPages::read)
            .map(SourceDocument::body);
    // then
    assertThat(body).contains("## 턴 1 · 2026-10-28T00:00:00Z\n\n사용자: 합성 질문");
  }

  @Test
  @DisplayName("텍스트와 이미지 블록 배열은 텍스트만 순서대로 본문에 넣는다")
  void userBlockArray() throws IOException {
    // given
    String line =
        TranscriptLines.user("첫 줄")
            .blocks(
                List.of(
                    Map.of("type", "text", "text", "첫 줄"),
                    Map.of(
                        "type",
                        "image",
                        "source",
                        Map.of("type", "base64", "media_type", "image/png", "data", "aGVsbG8=")),
                    Map.of("type", "text", "text", "둘째 줄")))
            .build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n사용자: 첫 줄\n둘째 줄");
  }

  @Test
  @DisplayName("질문 도구의 복수 선택 답은 쉼표로 이어 본문에 넣는다")
  void multiSelectAnswer() throws IOException {
    // given
    String ask =
        TranscriptLines.toolUse(
                "AskUserQuestion",
                Map.of(
                    "questions",
                    List.of(
                        Map.of(
                            "question",
                            "언어?",
                            "header",
                            "언어",
                            "multiSelect",
                            true,
                            "options",
                            List.of(
                                Map.of("label", "Java", "description", "JVM"),
                                Map.of("label", "Kotlin", "description", "JVM"))))))
            .build();
    String answer =
        TranscriptLines.toolResult("선택 결과")
            .answers(Map.of("언어?", List.of("Java", "Kotlin")))
            .build();
    items = collectItems((ask + "\n" + answer + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n질문: 언어?\n답: Java, Kotlin");
  }

  @Test
  @DisplayName("ai 제목이 없으면 첫 발화 첫 줄의 앞 60자를 제목으로 쓴다")
  void titleFallsBackToFirstUtterance() throws IOException {
    // given
    String line =
        TranscriptLines.user("가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마끝\n다음 줄")
            .build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String title = project().title();
    // then
    assertThat(title)
        .isEqualTo("project · 가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라");
  }

  @Test
  @DisplayName("대상 필드가 없는 도구 호출은 이름만 본문에 넣는다")
  void toolSummaryWithoutTarget() throws IOException {
    // given
    String line = TranscriptLines.toolUse("TodoWrite", Map.of("todos", List.of())).build();
    items = collectItems((line + "\n").getBytes(StandardCharsets.UTF_8));
    // when
    String body = project().body();
    // then
    assertThat(body).isEqualTo("## 턴 1 · 2026-10-28T00:00:00Z\n\n- 도구 TodoWrite");
  }
}
