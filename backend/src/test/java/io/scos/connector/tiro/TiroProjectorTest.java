package io.scos.connector.tiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.SourceDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro 노트 원본 → 문서 규칙(TS tiro-projector.spec.ts): 잠긴 문단·협업자 이메일·화자 귀속 전 전사는 본문에 넣지 않고, 화자 이름이 없으면
 * 라벨, AI 요약과 회의 문서는 제목으로 구분한다. 형식이 틀린 원본은 조용히 버리지 않고 실패한다.
 */
@DisplayName("Tiro 노트 원본은 TS 규칙대로 문서가 되고 틀린 형식은 실패한다(F8)")
class TiroProjectorTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String SOURCE = "tiro";
  static final String NOTE_ID = "note:n1";
  static final Instant OBSERVED = Instant.parse("2026-10-02T00:00:00Z");

  final TiroProjector projector = new TiroProjector(JSON);

  /** 원본 본문 JSON 빌더 — 기본은 제목만 있는 빈 노트. */
  static final class Raw {
    String note = "{\"title\":\"설계 회의\",\"webUrl\":\"https://tiro.ooo/n/n1\"}";
    String paragraphs = "[]";
    String summaries = "[]";
    String documents = "[]";
    String unavailable = "[]";

    Raw note(String json) {
      this.note = json;
      return this;
    }

    Raw paragraphs(String json) {
      this.paragraphs = json;
      return this;
    }

    Raw summaries(String json) {
      this.summaries = json;
      return this;
    }

    Raw documents(String json) {
      this.documents = json;
      return this;
    }

    Raw unavailable(String json) {
      this.unavailable = json;
      return this;
    }

    String json() {
      return "{\"note\":"
          + note
          + ",\"paragraphs\":"
          + paragraphs
          + ",\"summaries\":"
          + summaries
          + ",\"documents\":"
          + documents
          + ",\"unavailableResources\":"
          + unavailable
          + "}";
    }
  }

  static Raw raw() {
    return new Raw();
  }

  static RawItem item(String resourceType, Optional<String> createdAt, Optional<String> updatedAt) {
    return new RawItem(
        NOTE_ID,
        resourceType,
        "v1",
        Optional.empty(),
        createdAt,
        updatedAt,
        OBSERVED,
        "{}",
        Optional.of(new RawContent.Inline("application/json", "{}")));
  }

  static final RawItem NOTE =
      item(
          TiroProjector.RESOURCE_TYPE,
          Optional.of("2026-10-01T00:00:00Z"),
          Optional.of("2026-10-03T00:00:00Z"));

  SourceDocument project(Raw raw) {
    return projector
        .project(SOURCE, NOTE_ID, List.of(NOTE), i -> Optional.of(raw.json()))
        .orElseThrow();
  }

  String body(Raw raw) {
    return project(raw).body();
  }

  static final String SPEAKER_PARAGRAPH =
      "[{\"locked\":false,\"timeFrom\":\"2026-10-01T01:00:00Z\",\"transcript\":{\"content\":\"중복 전사\"},"
          + "\"diarizedSegments\":[{\"speaker\":{\"personName\":\"민수\",\"label\":\"SPEAKER_0\"},\"content\":\"변경 이유\"},"
          + "{\"speaker\":{\"personName\":null,\"label\":\"SPEAKER_1\"},\"content\":\"동의\"}]}]";

  @Nested
  @DisplayName("전사 문단이 있는 노트를 투영하면")
  class Paragraphs {
    @Test
    @DisplayName("화자 구간이 있으면 이름: 발화 줄로 쓴다")
    void writesSpeakerLines() {
      // when·then
      assertThat(body(raw().paragraphs(SPEAKER_PARAGRAPH))).contains("민수: 변경 이유\nSPEAKER_1: 동의");
    }

    @Test
    @DisplayName("화자 구간이 있으면 전사 본문은 쓰지 않는다")
    void skipsTranscriptWhenSpeakersExist() {
      // when·then
      assertThat(body(raw().paragraphs(SPEAKER_PARAGRAPH))).doesNotContain("중복 전사");
    }

    @Test
    @DisplayName("시작 시각이 있으면 문단 앞에 대괄호로 붙인다")
    void prefixesStartTime() {
      // when·then
      assertThat(body(raw().paragraphs(SPEAKER_PARAGRAPH)))
          .contains("## 전사 원문\n[2026-10-01T01:00:00Z]\n민수: 변경 이유");
    }

    @Test
    @DisplayName("화자 구간이 비면 전사 본문을 쓴다")
    void usesTranscriptWithoutSpeakers() {
      // given
      Raw raw =
          raw()
              .paragraphs(
                  "[{\"locked\":false,\"diarizedSegments\":[],\"transcript\":{\"content\":\"화자 없는 문단\"}}]");

      // when·then
      assertThat(body(raw)).endsWith("## 전사 원문\n화자 없는 문단");
    }

    @Test
    @DisplayName("문단은 빈 줄로 나눈다")
    void separatesParagraphs() {
      // given
      Raw raw =
          raw()
              .paragraphs(
                  "[{\"locked\":false,\"transcript\":{\"content\":\"첫째\"}},"
                      + "{\"locked\":false,\"transcript\":null},"
                      + "{\"locked\":false,\"transcript\":{\"content\":\"둘째\"}}]");

      // when·then
      assertThat(body(raw)).endsWith("## 전사 원문\n첫째\n\n둘째");
    }

    static final String LOCKED =
        "[{\"locked\":true,\"transcript\":{\"content\":\"MASKED\"}},"
            + "{\"locked\":true},{\"locked\":false,\"transcript\":{\"content\":\"공개\"}}]";

    @Test
    @DisplayName("잠긴 문단은 본문에 넣지 않는다")
    void excludesLockedParagraphs() {
      // when·then
      assertThat(body(raw().paragraphs(LOCKED))).doesNotContain("MASKED");
    }

    @Test
    @DisplayName("잠긴 문단 수를 본문 끝에 밝힌다")
    void reportsLockedCount() {
      // when·then
      assertThat(body(raw().paragraphs(LOCKED))).endsWith("\n\n전사 제한: 2개 문단은 Tiro에서 잠겨 있어 제외됨.");
    }

    @Test
    @DisplayName("잠긴 문단만 있으면 전사 원문 절이 없다")
    void onlyLockedHasNoTranscriptSection() {
      // when·then
      assertThat(body(raw().paragraphs("[{\"locked\":true}]"))).doesNotContain("## 전사 원문");
    }

    @Test
    @DisplayName("잠긴 문단이 없으면 전사 제한 줄도 없다")
    void noLockedNoNotice() {
      // when·then
      assertThat(body(raw().paragraphs(SPEAKER_PARAGRAPH))).doesNotContain("전사 제한");
    }
  }

  @Nested
  @DisplayName("요약과 회의 문서가 있는 노트를 투영하면")
  class SummariesAndDocuments {
    @Test
    @DisplayName("요약은 AI 요약 절로 쓴다")
    void writesSummaries() {
      // given
      Raw raw = raw().summaries("[{\"content\":{\"content\":\"요약입니다\"}},{\"content\":null}]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의\n\n## AI 요약\n요약입니다");
    }

    @Test
    @DisplayName("본문이 빈 요약은 쓰지 않는다")
    void skipsEmptySummary() {
      // given
      Raw raw = raw().summaries("[{\"content\":{\"content\":\"\"}}]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의");
    }

    @Test
    @DisplayName("회의 문서는 템플릿 제목 절에 비지 않은 섹션을 빈 줄로 잇는다")
    void writesDocumentSections() {
      // given
      Raw raw =
          raw()
              .documents(
                  "[{\"template\":{\"title\":\"회의록\"},\"sections\":[{\"content\":{\"content\":\"결정\"}},"
                      + "{\"content\":{\"content\":\"\"}},{\"content\":null},{\"content\":{\"content\":\"후속 작업\"}}]}]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의\n\n## 회의록\n결정\n\n후속 작업");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "\"template\":null,",
          "\"template\":{\"title\":null},",
          "\"template\":{},",
          "",
          "\"template\":\"\",",
          "\"template\":false,",
          "\"template\":0,"
        })
    @DisplayName("템플릿 제목이 없으면 AI 회의 문서 절이다")
    void defaultsTemplateTitle(String template) {
      // given
      Raw raw =
          raw().documents("[{" + template + "\"sections\":[{\"content\":{\"content\":\"본문\"}}]}]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의\n\n## AI 회의 문서\n본문");
    }

    @Test
    @DisplayName("섹션이 모두 비면 그 문서 절을 쓰지 않는다")
    void skipsEmptyDocument() {
      // given
      Raw raw = raw().documents("[{\"template\":{\"title\":\"회의록\"},\"sections\":[]}]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의");
    }

    @Test
    @DisplayName("요약·문서·전사 순서로 쓴다")
    void ordersSections() {
      // given
      Raw raw =
          raw()
              .paragraphs("[{\"locked\":false,\"transcript\":{\"content\":\"전사\"}}]")
              .documents("[{\"sections\":[{\"content\":{\"content\":\"문서\"}}]}]")
              .summaries("[{\"content\":{\"content\":\"요약\"}}]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의\n\n## AI 요약\n요약\n\n## AI 회의 문서\n문서\n\n## 전사 원문\n전사");
    }
  }

  @Nested
  @DisplayName("노트 정보를 투영하면")
  class NoteFields {
    @Test
    @DisplayName("협업자·참여자 정보는 본문에 넣지 않는다")
    void excludesCollaborators() {
      // given
      Raw raw =
          raw()
              .note(
                  "{\"title\":\"t\",\"collaborators\":[{\"email\":\"private@example.test\"}],"
                      + "\"participants\":[{\"name\":\"참석자\",\"email\":\"p@example.test\"}]}");

      // when·then
      assertThat(body(raw)).isEqualTo("# t");
    }

    @Test
    @DisplayName("녹음 시작 시각이 있으면 회의 일시 줄을 쓴다")
    void writesRecordingStart() {
      // given
      Raw raw = raw().note("{\"title\":\"t\",\"recordingStartAt\":\"2026-10-01T01:00:00Z\"}");

      // when·then
      assertThat(body(raw)).isEqualTo("# t\n\n회의 일시: 2026-10-01T01:00:00Z");
    }

    @Test
    @DisplayName("녹음 시작 시각이 null이면 회의 일시 줄이 없다")
    void skipsNullRecordingStart() {
      // given
      Raw raw = raw().note("{\"title\":\"t\",\"recordingStartAt\":null}");

      // when·then
      assertThat(body(raw)).isEqualTo("# t");
    }

    @Test
    @DisplayName("접근 제한 종류를 한국어 이름으로 밝힌다")
    void namesUnavailableResources() {
      // given
      Raw raw = raw().unavailable("[\"paragraphs\",\"summaries\",\"documents\",\"wiki\",7]");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의\n\n접근 제한으로 제외된 내용: 전사 원문, AI 요약, 회의 문서, wiki");
    }

    @Test
    @DisplayName("접근 제한 목록이 배열이 아니면 없는 것으로 본다")
    void ignoresMalformedUnavailable() {
      // given
      Raw raw = raw().unavailable("\"paragraphs\"");

      // when·then
      assertThat(body(raw)).isEqualTo("# 설계 회의");
    }

    @Test
    @DisplayName("제목은 노트 제목이다")
    void titleIsNoteTitle() {
      // when·then
      assertThat(project(raw()).title()).isEqualTo("설계 회의");
    }

    @Test
    @DisplayName("tiro.ooo 노트 주소를 uri로 쓴다")
    void usesTiroWebUrl() {
      // when·then
      assertThat(project(raw()).uri()).isEqualTo("https://tiro.ooo/n/n1");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "{\"title\":\"t\",\"webUrl\":\"https://example.test/n1\"}",
          "{\"title\":\"t\",\"webUrl\":\"http://tiro.ooo/n/n1\"}",
          "{\"title\":\"t\",\"webUrl\":7}",
          "{\"title\":\"t\"}"
        })
    @DisplayName("tiro.ooo 밖 주소면 tiro:// 문서 id를 uri로 쓴다")
    void fallsBackToTiroScheme(String note) {
      // when·then
      assertThat(project(raw().note(note)).uri()).isEqualTo("tiro://" + NOTE_ID);
    }

    @Test
    @DisplayName("문서 시각은 원본의 Tiro 생성·수정 시각이다")
    void usesSourceTimestamps() {
      // when
      SourceDocument doc = project(raw());

      // then
      assertThat(doc)
          .extracting(
              SourceDocument::sourceId, SourceDocument::createdAt, SourceDocument::updatedAt)
          .containsExactly(SOURCE, "2026-10-01T00:00:00Z", "2026-10-03T00:00:00Z");
    }

    @Test
    @DisplayName("Tiro 시각이 없으면 관측 시각을 쓴다")
    void fallsBackToObservedAt() {
      // given
      RawItem bare = item(TiroProjector.RESOURCE_TYPE, Optional.empty(), Optional.empty());

      // when
      SourceDocument doc =
          projector
              .project(SOURCE, NOTE_ID, List.of(bare), i -> Optional.of(raw().json()))
              .orElseThrow();

      // then
      assertThat(doc)
          .extracting(SourceDocument::createdAt, SourceDocument::updatedAt)
          .containsExactly("2026-10-02T00:00:00.000Z", "2026-10-02T00:00:00.000Z");
    }
  }

  @Nested
  @DisplayName("원본을 문서로 묶으면")
  class Grouping {
    static final RawItem OTHER = item("slack.message", Optional.empty(), Optional.empty());

    @Test
    @DisplayName("tiro.note 원본만 원본 id별 문서로 묶는다")
    void groupsNotesOnly() {
      // when·then
      assertThat(projector.groupIntoDocuments(List.of(OTHER, NOTE))).containsOnlyKeys(NOTE_ID);
    }

    @Test
    @DisplayName("tiro.note가 아닌 원본은 투영하지 않는다")
    void otherResourceProjectsNothing() {
      // when
      Optional<SourceDocument> doc =
          projector.project(SOURCE, NOTE_ID, List.of(OTHER), i -> Optional.of(raw().json()));

      // then
      assertThat(doc).isEmpty();
    }

    @Test
    @DisplayName("원본이 없으면 투영하지 않는다")
    void noItemsProjectsNothing() {
      // when
      Optional<SourceDocument> doc =
          projector.project(SOURCE, NOTE_ID, List.of(), i -> Optional.of(raw().json()));

      // then
      assertThat(doc).isEmpty();
    }
  }

  static Stream<Arguments> malformedRaw() {
    return Stream.of(
        arguments(
            "문단 목록이 null",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":null}"),
        arguments(
            "요약 목록이 객체",
            "{\"note\":{\"title\":\"t\"},\"summaries\":{},\"documents\":[],\"paragraphs\":[]}"),
        arguments("문서 목록이 없다", "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"paragraphs\":[]}"),
        arguments(
            "요약 항목이 객체가 아니다",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[1],\"documents\":[],\"paragraphs\":[]}"),
        arguments(
            "노트 제목이 수",
            "{\"note\":{\"title\":7},\"summaries\":[],\"documents\":[],\"paragraphs\":[]}"),
        arguments(
            "노트가 객체가 아니다", "{\"note\":\"t\",\"summaries\":[],\"documents\":[],\"paragraphs\":[]}"),
        arguments("노트가 없다", "{\"summaries\":[],\"documents\":[],\"paragraphs\":[]}"),
        arguments("원본이 배열", "[]"),
        arguments(
            "요약 본문이 문자열",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[{\"content\":\"요약\"}],\"documents\":[],\"paragraphs\":[]}"),
        arguments(
            "요약 본문 content가 수",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[{\"content\":{\"content\":1}}],\"documents\":[],\"paragraphs\":[]}"),
        arguments(
            "문서 섹션이 null",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[{\"sections\":null}],\"paragraphs\":[]}"),
        arguments(
            "템플릿이 비지 않은 문자열",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[{\"template\":\"회의록\",\"sections\":[{\"content\":{\"content\":\"c\"}}]}],\"paragraphs\":[]}"),
        arguments(
            "템플릿 제목이 수",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[{\"template\":{\"title\":3},\"sections\":[{\"content\":{\"content\":\"c\"}}]}],\"paragraphs\":[]}"),
        arguments(
            "화자 구간 content가 null",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":[{\"locked\":false,\"diarizedSegments\":[{\"speaker\":{\"label\":\"S\"},\"content\":null}]}]}"),
        arguments(
            "화자 라벨이 없다",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":[{\"locked\":false,\"diarizedSegments\":[{\"speaker\":{},\"content\":\"c\"}]}]}"),
        arguments(
            "화자 이름이 수",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":[{\"locked\":false,\"diarizedSegments\":[{\"speaker\":{\"label\":\"S\",\"personName\":1},\"content\":\"c\"}]}]}"),
        arguments(
            "화자가 없다",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":[{\"locked\":false,\"diarizedSegments\":[{\"content\":\"c\"}]}]}"),
        arguments(
            "화자 구간이 배열이 아니다",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":[{\"locked\":false,\"diarizedSegments\":\"x\"}]}"),
        arguments(
            "전사가 문자열",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[],\"paragraphs\":[{\"locked\":false,\"transcript\":\"x\"}]}"),
        arguments(
            "템플릿이 true",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[{\"template\":true,\"sections\":[{\"content\":{\"content\":\"c\"}}]}],\"paragraphs\":[]}"),
        arguments(
            "템플릿이 0이 아닌 수",
            "{\"note\":{\"title\":\"t\"},\"summaries\":[],\"documents\":[{\"template\":1,\"sections\":[{\"content\":{\"content\":\"c\"}}]}],\"paragraphs\":[]}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedRaw")
  @DisplayName("형식이 틀린 원본은 투영 실패다")
  void malformedRawFails(String name, String content) {
    // when·then
    assertThatThrownBy(
            () -> projector.project(SOURCE, NOTE_ID, List.of(NOTE), i -> Optional.of(content)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Invalid Tiro ");
  }

  @Test
  @DisplayName("원본 본문을 읽지 못하면 실패한다")
  void missingContentFails() {
    // when·then
    assertThatThrownBy(
            () -> projector.project(SOURCE, NOTE_ID, List.of(NOTE), i -> Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("kind는 tiro다")
  void kindIsTiro() {
    // when·then
    assertThat(projector.kind()).isEqualTo("tiro");
  }
}
