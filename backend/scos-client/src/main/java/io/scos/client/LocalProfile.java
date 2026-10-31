package io.scos.client;

import io.scos.mcp.McpServerProfile;
import java.util.Map;

/** scos-local이 MCP 클라이언트에 보이는 글: 사내 지식이 아니라 이 기기의 내 작업 기록 검색이다. */
public final class LocalProfile {
  static final String INSTRUCTIONS =
      """
      scos-local은 이 기기에서 나눈 Claude Code 세션 대화와 자동 메모리를 모은 내 작업 기록 검색입니다 (my own work history on this machine: past session turns and auto-memory, all projects).
      언제 쓰나: "어디까지 했지", "그때 왜 그렇게 했지", 지난 세션에서 정한 결정·이유·남은 일을 물을 때, 그리고 작업 중 이 저장소의 규칙·이름이 왜 그런지 궁금할 때 추측하기 전에 먼저 찾으세요. 사내 Slack·Notion·Jira 지식은 여기 없습니다(그건 scos).
      어떻게: 배경은 assemble_context 한 번부터, 후보를 훑으려면 search_context, 세션 전체는 read_document. 결과의 title에 프로젝트, 출처에 세션·날짜가 있으니 인용할 때 함께 적으세요.\
      """;

  private static final String SEARCH_DESCRIPTION =
      """
      내 Claude Code 세션 대화(턴 묶음)와 자동 메모리에서 관련 대화·메모 조각을 찾습니다. \
      어떤 문서·논의가 있는지 훑어 고를 때 씁니다 — 배경을 본문까지 한 번에 모으려면 assemble_context가 낫습니다. \
      질문이나 키워드로 질의하면 제목·발췌·출처·문서 id·점수가 담긴 상위 결과를 반환합니다. \
      freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. \
      특정 문서의 전체 내용이 필요하면 반환된 documentId로 read_document를 호출하세요. \
      계정·설정값처럼 바뀌는 값은 profile=current로 관련 결과를 최신순으로 조회하세요. \
      effort는 빠른 확인이면 low, 결정 전 조사면 high를 쓰세요(기본 medium).\
      """;

  private static final String ASSEMBLE_DESCRIPTION =
      """
      내 Claude Code 세션 대화(턴 묶음)와 자동 메모리에서 질문의 배경을 모을 때 먼저 부르는 도구입니다 — 지난 세션의 결정·이유·남은 일처럼 코드에 없는 맥락. \
      질의로 예산 내 최적 컨텍스트 묶음을 한 번에 조립합니다. search_context→read_document \
      왕복 없이, 검색·랭킹·토큰 예산 배치를 한 호출로 마쳐 제목·출처·문서 id·본문이 담긴 유닛 목록을 반환합니다. \
      freshness.stale=true면 데이터가 12시간 이상 묵었음을 알리고, latestCapturedAt=null은 최신성 확인 불가로 해석하세요. \
      tokenBudget은 기본 2000이며 500~8000 범위로 자동 조정됩니다. \
      계정·설정값처럼 바뀌는 값은 profile=current로 관련 결과를 최신순으로 조회하세요. \
      effort는 빠른 확인이면 low, 결정 전 조사면 high를 쓰세요(기본 medium).\
      """;

  /** 임베더가 없어(키워드 전용) 늘 빈 결과다 — 사내 서버 설명을 그대로 두면 쓸모 있는 도구로 보인다. */
  static final String RELATED_DESCRIPTION =
      "scos-local에서는 쓸 수 없습니다(unavailable in scos-local: keyword search only, no embeddings) — 늘 빈 결과를"
          + " 돌려줍니다. 관련 세션·메모는 search_context나 assemble_context로 찾으세요.";

  public static final McpServerProfile PROFILE =
      new McpServerProfile(
          "scos-local",
          McpServerProfile.ORGANIZATION.version(),
          INSTRUCTIONS,
          Map.of(
              "search_context",
              SEARCH_DESCRIPTION,
              "assemble_context",
              ASSEMBLE_DESCRIPTION,
              "related_documents",
              RELATED_DESCRIPTION));

  private LocalProfile() {}
}
