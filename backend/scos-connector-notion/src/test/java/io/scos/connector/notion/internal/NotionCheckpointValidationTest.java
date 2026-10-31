package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("확인점은 손상된 진행을 정상 재개로 해석하지 않는다")
class NotionCheckpointValidationTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidFields")
  @DisplayName("저장된 필드의 계약을 위반하면 확인점 오류로 거부한다")
  void rejectsMalformedProgress(String contract, String pointer, String json) {
    var context = new NotionCheckpointScenario();
    context.replace(pointer, json);

    // when·then
    assertThatThrownBy(context::resume)
        .isInstanceOf(NotionProtocolException.class)
        .hasMessage(NotionProtocolException.INVALID_CHECKPOINT);
  }

  @Test
  @DisplayName("양수인 최소 증분 주기 1초는 재개할 수 있다")
  void acceptsOneSecondInterval() {
    var context = new NotionCheckpointScenario();
    context.replace("/progress/incrementalIntervalSeconds", "1");

    // then
    assertThat(
            context
                .resume()
                .beginSearch(SearchPhase.ACTIVE, NotionCheckpointScenario.SEARCH_FINISHED)
                .startedAt())
        .isEqualTo(NotionCheckpointScenario.START);
  }

  @Test
  @DisplayName("목록 완료 필드가 없던 레거시 Search도 저장된 커서부터 재개한다")
  void legacySearchWithoutListingCompleteRemainsReadable() {
    var context = new NotionCheckpointScenario();
    context.remove("/incremental/active/scan/listingComplete");
    var resumed = context.resume();
    // then
    assertThat(
            resumed
                .beginSearch(SearchPhase.ACTIVE, NotionCheckpointScenario.SEARCH_FINISHED)
                .cursor())
        .contains("search-cursor");
  }

  @Test
  @DisplayName("레거시 미완료 본문에 인계 버전 없이 남은 손상된 자식도 거부한다")
  void legacyPendingBodyCannotHideMalformedDiscoveredWork() {
    var context = new NotionCheckpointScenario();
    context.remove("/refreshes/0/discoveredVersion");
    context.replace("/refreshes/0/discovered", "[{\"kind\":\"unknown\"}]");
    // when·then
    assertThatThrownBy(context::resume).isInstanceOf(NotionProtocolException.class);
  }

  @Test
  @DisplayName("목록 완료 필드가 없던 레거시 행 scan도 진행 중 커서를 보존한다")
  void legacyRowsWithCursorRemainResumable() {
    var context = new NotionCheckpointScenario();
    context.remove("/incremental/containers/0/scan/listingComplete");
    var resumed = context.resume();
    // then
    assertThat(
            resumed.rows(NotionFixtures.DATA_SOURCE, NotionPageRefresh.Path.INCREMENTAL).cursor())
        .contains("row-cursor");
  }

  static Stream<Arguments> invalidFields() {
    return Stream.of(
        Arguments.of("진행 봉투의 알 수 없는 필드", "/progress/unexpected", "true"),
        Arguments.of("증분 봉투의 알 수 없는 필드", "/incremental/unexpected", "true"),
        Arguments.of("봉투의 알 수 없는 필드", "/unexpected", "true"),
        Arguments.of("본문 진행은 배열", "/refreshes", "{}"),
        Arguments.of("본문 진행의 알 수 없는 필드", "/refreshes/0/unexpected", "true"),
        Arguments.of("빈 발견 버전", "/refreshes/0/discoveredVersion", "\"\""),
        Arguments.of("발견 버전 없는 자식 인계 금지", "/refreshes/0/discovered", "[]"),
        Arguments.of(
            "본문 진행에는 블록 열거만 허용", "/refreshes/0/work", "[{\"kind\":\"page\",\"pageId\":\"child\"}]"),
        Arguments.of("인계 작업의 형식", "/refreshes/0/discovered", "[{\"kind\":\"unknown\"}]"),
        Arguments.of("root 범위의 알 수 없는 필드", "/scope/unexpected", "true"),
        Arguments.of("검색 경로의 알 수 없는 필드", "/incremental/active/unexpected", "true"),
        Arguments.of("검색 진행의 알 수 없는 필드", "/incremental/active/scan/unexpected", "true"),
        Arguments.of("검색 완료 표시는 boolean", "/incremental/active/scan/listingComplete", "\"yes\""),
        Arguments.of("재시도 작업은 배열", "/incremental/active/retryWork", "{}"),
        Arguments.of("검색 작업은 배열", "/incremental/active/scan/work", "{}"),
        Arguments.of("컨테이너의 알 수 없는 필드", "/incremental/containers/0/unexpected", "true"),
        Arguments.of("행 진행의 알 수 없는 필드", "/incremental/containers/0/scan/unexpected", "true"),
        Arguments.of(
            "행 생성 경계 역전", "/incremental/containers/0/scan/lastCreated", "\"2026-10-24T00:00:00Z\""),
        Arguments.of("증분 주기 0 금지", "/progress/incrementalIntervalSeconds", "0"));
  }
}
