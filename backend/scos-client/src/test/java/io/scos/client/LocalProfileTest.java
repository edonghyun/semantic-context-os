package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.mcp.McpServerProfile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("scos-local 프로필은 내 작업 기록 검색으로 소개한다")
class LocalProfileTest {
  private static final String LEAD = "내 Claude Code 세션 대화(턴 묶음)와 자동 메모리에서";

  /** 계획(Task D6)의 안내문 그대로 — 제품의 텍스트 블록과 다른 표기로 적어 줄바꿈·따옴표까지 비교한다. */
  @SuppressWarnings("StringConcatToTextBlock")
  private static final String PLANNED_INSTRUCTIONS =
      String.join(
          "\n",
          "scos-local은 이 기기에서 나눈 Claude Code 세션 대화와 자동 메모리를 모은 내 작업 기록 검색입니다 (my own work history on this machine: past session turns and auto-memory, all projects).",
          "언제 쓰나: \"어디까지 했지\", \"그때 왜 그렇게 했지\", 지난 세션에서 정한 결정·이유·남은 일을 물을 때, 그리고 작업 중 이 저장소의 규칙·이름이 왜 그런지 궁금할 때 추측하기 전에 먼저 찾으세요. 사내 Slack·Notion·Jira 지식은 여기 없습니다(그건 scos).",
          "어떻게: 배경은 assemble_context 한 번부터, 후보를 훑으려면 search_context, 세션 전체는 read_document. 결과의 title에 프로젝트, 출처에 세션·날짜가 있으니 인용할 때 함께 적으세요.");

  @Test
  @DisplayName("안내문은 계획의 문구 그대로다")
  void instructionsAsPlanned() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    String instructions = profile.instructions();

    // then
    assertThat(instructions).isEqualTo(PLANNED_INSTRUCTIONS);
  }

  @Test
  @DisplayName("검색·조립·관련 문서 도구 설명을 바꾸고 검색·조립은 내 작업 기록으로 시작한다")
  void rewritesSearchAssembleAndRelated() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    var descriptions = profile.toolDescriptions();

    // then
    assertThat(descriptions.keySet())
        .containsExactlyInAnyOrder("search_context", "assemble_context", "related_documents");
    assertThat(List.of(descriptions.get("search_context"), descriptions.get("assemble_context")))
        .allMatch(d -> d.startsWith(LEAD));
  }

  @Test
  @DisplayName("관련 문서 도구 설명은 scos-local에서 쓸 수 없다고 밝힌다")
  void relatedIsUnavailable() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    String related = profile.toolDescriptions().get("related_documents");

    // then
    assertThat(related)
        .startsWith("scos-local에서는 쓸 수 없습니다")
        .contains("keyword search only")
        .doesNotContain("Slack", "Notion");
  }

  @Test
  @DisplayName("안내문의 사용법은 관련 문서 도구를 권하지 않는다")
  void instructionsDoNotSuggestRelated() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    String instructions = profile.instructions();

    // then
    assertThat(instructions).doesNotContain("related_documents");
  }

  @Test
  @DisplayName("검색 도구는 로컬 기록의 용도와 사용법을 완결된 문장으로 설명한다")
  void searchDescriptionIsComplete() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    String description = profile.toolDescriptions().get("search_context");

    // then
    assertThat(description)
        .isEqualTo(
            """
        내 Claude Code 세션 대화(턴 묶음)와 자동 메모리에서 관련 대화·메모 조각을 찾습니다. \
        어떤 문서·논의가 있는지 훑어 고를 때 씁니다 — 배경을 본문까지 한 번에 모으려면 assemble_context가 낫습니다. \
        질문이나 키워드로 질의하면 제목·발췌·출처·문서 id·점수가 담긴 상위 결과를 반환합니다. \
        freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. \
        특정 문서의 전체 내용이 필요하면 반환된 documentId로 read_document를 호출하세요. \
        계정·설정값처럼 바뀌는 값은 profile=current로 관련 결과를 최신순으로 조회하세요. \
        effort는 빠른 확인이면 low, 결정 전 조사면 high를 쓰세요(기본 medium).\
        """);
  }

  @Test
  @DisplayName("조립 도구는 로컬 기록의 용도와 사용법을 완결된 문장으로 설명한다")
  void assembleDescriptionIsComplete() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    String description = profile.toolDescriptions().get("assemble_context");

    // then
    assertThat(description)
        .isEqualTo(
            """
        내 Claude Code 세션 대화(턴 묶음)와 자동 메모리에서 질문의 배경을 모을 때 먼저 부르는 도구입니다 — 지난 세션의 결정·이유·남은 일처럼 코드에 없는 맥락. \
        질의로 예산 내 최적 컨텍스트 묶음을 한 번에 조립합니다. search_context→read_document \
        왕복 없이, 검색·랭킹·토큰 예산 배치를 한 호출로 마쳐 제목·출처·문서 id·본문이 담긴 유닛 목록을 반환합니다. \
        freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. \
        tokenBudget은 기본 2000이며 500~8000 범위로 자동 조정됩니다. \
        계정·설정값처럼 바뀌는 값은 profile=current로 관련 결과를 최신순으로 조회하세요. \
        effort는 빠른 확인이면 low, 결정 전 조사면 high를 쓰세요(기본 medium).\
        """);
  }

  @Test
  @DisplayName("이름은 scos-local, 버전은 사내 서버와 같다")
  void nameAndVersion() {
    // given
    McpServerProfile profile = LocalProfile.PROFILE;

    // when
    String version = profile.version();

    // then
    assertThat(profile.name()).isEqualTo("scos-local");
    assertThat(version).isEqualTo(McpServerProfile.ORGANIZATION.version());
  }
}
