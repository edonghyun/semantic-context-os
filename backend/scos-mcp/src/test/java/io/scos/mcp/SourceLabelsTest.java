package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("응답 source는 uri 분류값이고, 선택하면 분류 불가에 원천 id를 쓴다(B10)")
class SourceLabelsTest {

  @Nested
  @DisplayName("기본 규칙(URI_ONLY)에서")
  class UriOnly {
    @ParameterizedTest(name = "{0} (원천 {1}) → unknown")
    @DisplayName("분류할 수 없는 uri는 원천 id가 있어도 unknown이다")
    @CsvSource({
      "https://tiro.ooo/n/1, tiro-main",
      "tiro://note-1, tiro-main",
      "https://board.example/notice/1, board-a"
    })
    void unclassifiedStaysUnknown(String uri, String sourceId) {
      // when
      String actual = SourceLabels.URI_ONLY.label(uri, sourceId);

      // then
      assertThat(actual).isEqualTo("unknown");
    }
  }

  @Nested
  @DisplayName("원천 id 대체 규칙(SOURCE_ID_FALLBACK)에서")
  class SourceIdFallback {
    @ParameterizedTest(name = "{0} (원천 {1}) → {2}")
    @DisplayName("uri로 분류되는 출처는 원천 id가 있어도 분류값이다")
    @CsvSource({
      "slack://C01/p1, slack-main, slack",
      "notion:page-1, notion-main, notion",
      "https://www.notion.so/page-1, notion-main, notion",
      "jira:PROJ-1, jira-main, jira",
      "https://acme.atlassian.net/browse/PROJ-1, jira-main, jira",
      "gdrive:file-1, drive-main, google-drive",
      "https://docs.google.com/document/d/1, drive-main, google-drive",
      "claude-transcript://d/s1, transcripts, transcript",
      "claude-memory://d/m.md, memories, memory",
      "file:///opt/x/a.md, docs, document",
      "git://scos/8b36e33a, repo, git"
    })
    void classifiedKindWinsOverSourceId(String uri, String sourceId, String label) {
      // when
      String actual = SourceLabels.SOURCE_ID_FALLBACK.label(uri, sourceId);

      // then
      assertThat(actual).isEqualTo(label);
    }

    @ParameterizedTest(name = "{0} (원천 {1}) → {1}")
    @DisplayName("uri로 분류할 수 없으면 원천 id를 출처로 쓴다")
    @CsvSource({
      "https://board.example/notice/1, board-a",
      "http://board.example/notice/1, board-a",
      "https://acme.atlassian.net/wiki/x, wiki-main",
      "fixture:doc-1, fixture",
      "'not a uri', board-a"
    })
    void unclassifiedUsesSourceId(String uri, String sourceId) {
      // when
      String actual = SourceLabels.SOURCE_ID_FALLBACK.label(uri, sourceId);

      // then
      assertThat(actual).isEqualTo(sourceId);
    }

    @ParameterizedTest(name = "원천 id \"{0}\"")
    @DisplayName("분류할 수 없고 원천 id도 비면 unknown이다")
    @ValueSource(strings = {"", " "})
    void blankSourceIdIsUnknown(String sourceId) {
      // when
      String actual =
          SourceLabels.SOURCE_ID_FALLBACK.label("https://board.example/notice/1", sourceId);

      // then
      assertThat(actual).isEqualTo("unknown");
    }
  }
}
