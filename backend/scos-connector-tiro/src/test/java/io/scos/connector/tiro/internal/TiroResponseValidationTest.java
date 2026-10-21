package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroScenario.LIST;
import static io.scos.connector.tiro.internal.TiroScenario.note;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.scos.connector.tiro.internal.TiroScenario.Collection;
import io.scos.spi.connector.CallFailure.Kind;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 투영이 읽는 필드를 체크포인트 전진 전에 검사한다(TS tiro-adapter.ts cursorPage·validateParagraph·문서 검사). 형식이 틀린 응답 하나가
 * 첫 페이지(n-alpha)를 깨뜨리면 run은 invalid_provider_response로 실패하고 그 페이지는 커밋되지 않는다. 대조군: TS가 받아들이는 형식(잠긴
 * 문단의 전사, null 이름, totalSize 없음 등)은 통과한다.
 */
@DisplayName("Tiro 응답 형식은 체크포인트 전진 전에 TS처럼 검사한다")
class TiroResponseValidationTest {
  static final String PARAGRAPHS = note("n-alpha", "paragraphs");
  static final String SUMMARIES = note("n-alpha", "summaries");
  static final String SUMMARY = note("n-alpha", "summaries", "7");
  static final String DOCUMENT = note("n-alpha", "documents", "11");
  static final String DETAIL = note("n-alpha");
  static final String NOTE_FIELDS =
      "\"createdAt\":\"2026-10-01T01:00:00Z\",\"updatedAt\":\"2026-10-01T02:00:00Z\"";

  final TiroScenario tiro = new TiroScenario();

  @AfterEach
  void tearDown() {
    tiro.close();
  }

  static String paragraph(String fields) {
    return "{\"content\":[{\"uuid\":\"p\"," + fields + "}],\"nextCursor\":null}";
  }

  static String summary(String fields) {
    return "{\"id\":7,\"noteGuid\":\"n-alpha\"," + fields + "}";
  }

  static String document(String fields) {
    return "{\"id\":11,\"noteGuid\":\"n-alpha\"," + fields + "}";
  }

  static String noteDetail(String fields) {
    return "{\"guid\":\"n-alpha\",\"workspaceGuid\":\"ws-synthetic\"," + fields + "}";
  }

  static final String TEXT = "{\"type\":\"text/plain\",\"content\":\"본문\"}";

  static Stream<Arguments> malformed() {
    return Stream.of(
        arguments("목록 봉투가 없다", LIST, "{}"),
        arguments("목록 nextCursor가 없다", LIST, "{\"content\":[]}"),
        arguments("목록 nextCursor가 빈 문자열", LIST, "{\"content\":[],\"nextCursor\":\"\"}"),
        arguments("목록 nextCursor가 수", LIST, "{\"content\":[],\"nextCursor\":5}"),
        arguments("목록 content가 배열이 아니다", LIST, "{\"content\":\"x\",\"nextCursor\":null}"),
        arguments("목록 항목이 객체가 아니다", LIST, "{\"content\":[1],\"nextCursor\":null}"),
        arguments("목록 응답이 null", LIST, "null"),
        arguments("노트 guid에 금지 문자", LIST, "{\"content\":[{\"guid\":\"a/b\"}],\"nextCursor\":null}"),
        arguments("노트 guid가 수", LIST, "{\"content\":[{\"guid\":7}],\"nextCursor\":null}"),
        arguments("노트 guid가 없다", LIST, "{\"content\":[{}],\"nextCursor\":null}"),
        arguments(
            "다른 워크스페이스의 노트",
            DETAIL,
            "{\"guid\":\"n-alpha\",\"workspaceGuid\":\"private\",\"title\":\"t\",\"webUrl\":\"u\","
                + NOTE_FIELDS
                + "}"),
        arguments(
            "요청과 다른 guid의 노트",
            DETAIL,
            "{\"guid\":\"n-other\",\"workspaceGuid\":\"ws-synthetic\",\"title\":\"t\",\"webUrl\":\"u\","
                + NOTE_FIELDS
                + "}"),
        arguments(
            "노트 workspaceGuid가 없다",
            DETAIL,
            "{\"guid\":\"n-alpha\",\"title\":\"t\",\"webUrl\":\"u\"," + NOTE_FIELDS + "}"),
        arguments("노트 제목이 없다", DETAIL, noteDetail("\"webUrl\":\"u\"," + NOTE_FIELDS)),
        arguments(
            "노트 webUrl이 수", DETAIL, noteDetail("\"title\":\"t\",\"webUrl\":1," + NOTE_FIELDS)),
        arguments(
            "노트 createdAt이 없다",
            DETAIL,
            noteDetail("\"title\":\"t\",\"webUrl\":\"u\",\"updatedAt\":\"2026-10-01T02:00:00Z\"")),
        arguments(
            "노트 updatedAt이 없다",
            DETAIL,
            noteDetail("\"title\":\"t\",\"webUrl\":\"u\",\"createdAt\":\"2026-10-01T01:00:00Z\"")),
        arguments(
            "노트 updatedAt이 시각이 아니다",
            DETAIL,
            noteDetail(
                "\"title\":\"t\",\"webUrl\":\"u\",\"createdAt\":\"c\",\"updatedAt\":\"yesterday\"")),
        arguments(
            "문단 uuid가 없다", PARAGRAPHS, "{\"content\":[{\"locked\":false}],\"nextCursor\":null}"),
        arguments("문단 locked가 문자열", PARAGRAPHS, paragraph("\"locked\":\"no\"")),
        arguments("문단 locked가 없다", PARAGRAPHS, paragraph("\"transcript\":" + TEXT)),
        arguments(
            "전사 content가 수",
            PARAGRAPHS,
            paragraph("\"locked\":false,\"transcript\":{\"content\":1}")),
        arguments("전사가 문자열", PARAGRAPHS, paragraph("\"locked\":false,\"transcript\":\"x\"")),
        arguments(
            "화자 구간이 배열이 아니다", PARAGRAPHS, paragraph("\"locked\":false,\"diarizedSegments\":\"x\"")),
        arguments(
            "화자 구간 content가 null",
            PARAGRAPHS,
            paragraph(
                "\"locked\":false,\"diarizedSegments\":[{\"speaker\":{\"label\":\"S\"},\"content\":null}]")),
        arguments(
            "화자 라벨이 없다",
            PARAGRAPHS,
            paragraph(
                "\"locked\":false,\"diarizedSegments\":[{\"speaker\":{},\"content\":\"c\"}]")),
        arguments(
            "화자 이름이 수",
            PARAGRAPHS,
            paragraph(
                "\"locked\":false,\"diarizedSegments\":[{\"speaker\":{\"label\":\"S\",\"personName\":1},\"content\":\"c\"}]")),
        arguments(
            "화자가 객체가 아니다",
            PARAGRAPHS,
            paragraph(
                "\"locked\":false,\"diarizedSegments\":[{\"speaker\":\"S\",\"content\":\"c\"}]")),
        arguments(
            "화자 구간이 객체가 아니다", PARAGRAPHS, paragraph("\"locked\":false,\"diarizedSegments\":[1]")),
        arguments("문단 커서가 같은 곳을 돈다", PARAGRAPHS, "{\"content\":[],\"nextCursor\":\"same\"}"),
        arguments("문단 nextCursor가 없다", PARAGRAPHS, "{\"content\":[]}"),
        arguments("요약 목록 totalSize가 개수와 다르다", SUMMARIES, "{\"content\":[],\"totalSize\":1}"),
        arguments("요약 목록 totalSize가 null", SUMMARIES, "{\"content\":[],\"totalSize\":null}"),
        arguments("요약 목록 totalSize가 문자열", SUMMARIES, "{\"content\":[],\"totalSize\":\"0\"}"),
        arguments("요약 목록 content가 없다", SUMMARIES, "{\"totalSize\":0}"),
        arguments("요약 목록 content가 객체", SUMMARIES, "{\"content\":{},\"totalSize\":0}"),
        arguments("요약 id가 음수", SUMMARIES, "{\"content\":[{\"id\":-1}],\"totalSize\":1}"),
        arguments("요약 id가 소수", SUMMARIES, "{\"content\":[{\"id\":1.5}],\"totalSize\":1}"),
        arguments("요약 id가 문자열", SUMMARIES, "{\"content\":[{\"id\":\"7\"}],\"totalSize\":1}"),
        arguments(
            "요약 id가 안전 정수 밖",
            SUMMARIES,
            "{\"content\":[{\"id\":9007199254740992}],\"totalSize\":1}"),
        arguments("요약 id가 없다", SUMMARIES, "{\"content\":[{}],\"totalSize\":1}"),
        arguments(
            "요약 상세 id가 다르다",
            SUMMARY,
            "{\"id\":8,\"noteGuid\":\"n-alpha\",\"content\":" + TEXT + "}"),
        arguments(
            "요약 상세 id가 문자열",
            SUMMARY,
            "{\"id\":\"7\",\"noteGuid\":\"n-alpha\",\"content\":" + TEXT + "}"),
        arguments("요약 상세 id가 없다", SUMMARY, "{\"noteGuid\":\"n-alpha\",\"content\":" + TEXT + "}"),
        arguments(
            "요약이 다른 노트의 것", SUMMARY, "{\"id\":7,\"noteGuid\":\"n-beta\",\"content\":" + TEXT + "}"),
        arguments("요약 noteGuid가 없다", SUMMARY, "{\"id\":7,\"content\":" + TEXT + "}"),
        arguments("요약이 잘렸다", SUMMARY, summary("\"truncated\":true,\"content\":" + TEXT)),
        arguments("요약 본문이 없다", SUMMARY, summary("\"updatedAt\":\"2026-10-03T09:00:00Z\"")),
        arguments("요약 본문 content가 수", SUMMARY, summary("\"content\":{\"content\":3}")),
        arguments(
            "요약 updatedAt이 시각이 아니다",
            SUMMARY,
            summary("\"content\":" + TEXT + ",\"updatedAt\":\"soon\"")),
        arguments(
            "요약 updatedAt이 null", SUMMARY, summary("\"content\":" + TEXT + ",\"updatedAt\":null")),
        arguments("요약 updatedAt이 수", SUMMARY, summary("\"content\":" + TEXT + ",\"updatedAt\":1")),
        arguments("문서 섹션이 없다", DOCUMENT, document("\"template\":null")),
        arguments("문서 섹션이 객체", DOCUMENT, document("\"sections\":{}")),
        arguments("문서 섹션 본문이 없다", DOCUMENT, document("\"sections\":[{\"templateSectionId\":1}]")),
        arguments("문서 섹션이 객체가 아니다", DOCUMENT, document("\"sections\":[\"x\"]")),
        arguments(
            "문서 둘째 섹션 본문이 없다",
            DOCUMENT,
            document("\"sections\":[{\"content\":" + TEXT + "},{\"content\":null}]")),
        arguments("문서가 잘렸다", DOCUMENT, document("\"truncated\":true,\"sections\":[]")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformed")
  @DisplayName("형식이 틀린 응답은 invalid_provider_response로 실패한다")
  void malformedResponseFails(String name, String path, String body) {
    // given
    tiro.answer(path, body);

    // when
    Collection collection = tiro.collect();

    // then
    assertThat(collection.failureKind()).contains(Kind.INVALID_PROVIDER_RESPONSE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformed")
  @DisplayName("형식이 틀린 응답이 있는 목록 페이지는 내지 않는다")
  void malformedResponseCommitsNothing(String name, String path, String body) {
    // given
    tiro.answer(path, body);

    // when
    Collection collection = tiro.collect();

    // then
    assertThat(collection.pages()).isEmpty();
  }

  static final String LOOPING_LIST = "{\"content\":[],\"nextCursor\":\"same\"}";

  @Test
  @DisplayName("목록 커서가 같은 곳을 돌면 invalid_provider_response로 실패한다")
  void loopingListCursorFails() {
    // given
    tiro.answer(LIST, LOOPING_LIST);

    // when
    Collection collection = tiro.collect();

    // then
    assertThat(collection.failureKind()).contains(Kind.INVALID_PROVIDER_RESPONSE);
  }

  @Test
  @DisplayName("목록 커서가 같은 곳을 돌면 첫 페이지 뒤에 멈춘다")
  void loopingListCursorStopsAfterFirstPage() {
    // given
    tiro.answer(LIST, LOOPING_LIST);

    // when
    Collection collection = tiro.collect();

    // then
    assertThat(collection.pages()).hasSize(1);
  }

  @Test
  @DisplayName("문단 커서가 같은 곳을 돌면 같은 커서로 다시 요청하지 않는다")
  void loopingParagraphCursorIsNotRequestedAgain() {
    // given
    tiro.answer(PARAGRAPHS, "{\"content\":[],\"nextCursor\":\"same\"}");

    // when
    tiro.collect();

    // then
    assertThat(tiro.requests()).filteredOn(r -> r.startsWith(PARAGRAPHS)).hasSize(2);
  }

  static Stream<Arguments> accepted() {
    return Stream.of(
        arguments(
            "잠긴 문단의 전사는 검사하지 않는다",
            Map.of(PARAGRAPHS, paragraph("\"locked\":true,\"transcript\":\"x\""))),
        arguments(
            "전사가 null", Map.of(PARAGRAPHS, paragraph("\"locked\":false,\"transcript\":null"))),
        arguments(
            "화자 구간이 null",
            Map.of(PARAGRAPHS, paragraph("\"locked\":false,\"diarizedSegments\":null"))),
        arguments(
            "화자 이름이 null",
            Map.of(
                PARAGRAPHS,
                paragraph(
                    "\"locked\":false,\"diarizedSegments\":[{\"speaker\":{\"label\":\"S\",\"personName\":null},\"content\":\"c\"}]"))),
        arguments("요약 목록에 totalSize가 없다", Map.of(SUMMARIES, "{\"content\":[]}")),
        arguments(
            "요약 목록 totalSize가 1.0",
            Map.of(SUMMARIES, "{\"content\":[{\"id\":7}],\"totalSize\":1.0}")),
        arguments("요약 id가 7.0", Map.of(SUMMARIES, "{\"content\":[{\"id\":7.0}],\"totalSize\":1}")),
        arguments(
            "요약 id가 0",
            Map.of(
                SUMMARIES,
                "{\"content\":[{\"id\":0}],\"totalSize\":1}",
                note("n-alpha", "summaries", "0"),
                "{\"id\":0,\"noteGuid\":\"n-alpha\",\"content\":" + TEXT + "}")),
        arguments(
            "요약 id가 안전 정수 상한",
            Map.of(
                SUMMARIES,
                "{\"content\":[{\"id\":9007199254740991}],\"totalSize\":1}",
                note("n-alpha", "summaries", "9007199254740991"),
                "{\"id\":9007199254740991,\"noteGuid\":\"n-alpha\",\"content\":" + TEXT + "}")),
        arguments(
            "잘리지 않았다고 명시한 요약", Map.of(SUMMARY, summary("\"truncated\":false,\"content\":" + TEXT))),
        arguments(
            "truncated가 문자열인 요약",
            Map.of(SUMMARY, summary("\"truncated\":\"true\",\"content\":" + TEXT))),
        arguments("updatedAt이 없는 요약", Map.of(SUMMARY, summary("\"content\":" + TEXT))),
        arguments("섹션이 빈 배열인 문서", Map.of(DOCUMENT, document("\"sections\":[]"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("accepted")
  @DisplayName("TS가 받아들이는 형식은 통과한다")
  void acceptedResponseCompletes(String name, Map<String, String> answers) {
    // given
    answers.forEach(tiro::answer);

    // when
    Collection collection = tiro.collect();

    // then
    assertThat(collection.failureKind()).isEmpty();
  }
}
