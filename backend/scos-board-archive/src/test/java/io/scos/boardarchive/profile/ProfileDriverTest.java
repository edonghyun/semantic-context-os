package io.scos.boardarchive.profile;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.driver.*;
import java.util.*;
import org.junit.jupiter.api.*;

@DisplayName("설정형 드라이버는 합성 HTML과 JSON을 같은 계약으로 읽는다")
class ProfileDriverTest {
  ProfileScenario s;

  @BeforeEach
  void setup() throws Exception {
    s = new ProfileScenario();
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("고정글 선택자로 고정 여부를 읽는다")
  void pinnedSelectorMarksPinned() {
    var page = s.list();
    // then
    assertThat(page.posts().getFirst().pinned()).isTrue();
  }

  @Test
  @DisplayName("추적 쿼리만 원문 URL에서 제거한다")
  void canonicalizesUrlByDroppingTrackingParams() {
    s.list();
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class, f -> assertThat(f.url()).isEqualTo(s.canonicalPostUrl()));
  }

  @Test
  @DisplayName("본문과 첨부 URL을 가져온다")
  void fetchesBodyAndAttachments() {
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f -> {
              assertThat(f.text()).isEqualTo("Paragraph one. Paragraph two.");
              assertThat(f.attachments()).containsExactly(s.attachmentUrl());
            });
  }

  @Test
  @DisplayName("JSON Pointer 설정도 같은 필드를 읽는다")
  void jsonProfileReadsSameFields() {
    s.use("json", false);
    s.list();
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f -> assertThat(f.text()).isEqualTo("Paragraph one. Paragraph two."));
  }

  @Test
  @DisplayName("HTML raw에서 신원 메타 필드를 제거한다")
  void htmlRawDropsIdentityFields() {
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f.raw())
                    .doesNotContain("private-key", "private-contact", "data-member-id"));
  }

  @Test
  @DisplayName("JSON raw의 중첩 신원 필드를 제거한다")
  void jsonRawDropsIdentityFields() {
    s.use("json", false);
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f.raw())
                    .doesNotContain("private-key", "private-contact", "memberId", "email"));
  }

  @Test
  @DisplayName("작성자 표시명을 보존한다")
  void preservesAuthorDisplayName() {
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class, f -> assertThat(f.author()).isEqualTo("Writer"));
  }

  @Test
  @DisplayName("본문 추출 전에 설정된 신원 요소를 제거한다")
  void sanitizesBeforeExtractingText() {
    s.site.respond(
        "/post/12",
        200,
        "<h1 class='title'>First</h1><time class='date'>2026.11.17</time><div class='body'>Public<span class='private-contact'>private-contact</span><input type='email' value='private-key'><a href='mailto:private-contact'>private-contact</a></div>");
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f -> {
              assertThat(f.text()).isEqualTo("Public");
              assertThat(f.raw()).doesNotContain("private-contact", "private-key");
            });
  }

  @Test
  @DisplayName("계정 식별 속성과 확장 JSON 신원 키도 제거한다")
  void removesAdditionalIdentityMetadata() {
    s.use("json", false);
    s.site.respond(
        "/post/12",
        200,
        "{\"title\":\"First\",\"date\":\"2026.11.17\",\"body\":\"Public\",\"emailAddress\":\"private-contact\",\"metadata\":{\"privateKey\":\"private-key\"}}");
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f -> assertThat(f.raw()).doesNotContain("private-contact", "private-key"));
  }

  @Test
  @DisplayName("목록에만 있던 상대 날짜 원문도 raw 메타에 남긴다")
  void preservesOriginalListDateInRawMetadata() {
    s.site.respond(
        "/list?page=1",
        200,
        "<li class='post'><a class='title' href='/post/12'>First</a><time class='date'>3시간 전</time></li>");
    s.list();
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class, f -> assertThat(f.raw()).contains("\"listedDate\":\"3시간 전\""));
  }

  @Test
  @DisplayName("배열의 신원 선택자 여러 개를 적용해도 나머지 원소를 보존한다 (§3)")
  void removesArrayIdentityPointersWithoutIndexShift() {
    // given
    s.useIdentitySelectors(List.of("/metadata/0", "/metadata/1"));
    s.site.respond(
        "/post/12",
        200,
        "{\"title\":\"First\",\"date\":\"2026.11.17\",\"body\":\"Public\",\"metadata\":[\"private-first\",\"private-second\",\"public-meta\"]}");
    // when
    var result = s.fetch();
    // then
    assertThat(result)
        .isInstanceOfSatisfying(
            FetchResult.Found.class,
            f ->
                assertThat(f.raw())
                    .doesNotContain("private-first", "private-second")
                    .contains("public-meta"));
  }

  @Test
  @DisplayName("두 번째 HTML 페이지가 비면 다음 커서 없이 끝난다 (§7)")
  void secondHtmlPageEndsListing() {
    // given
    String cursor = s.site.uri("/list?page=2").toString();
    // when
    var page = s.next(cursor);
    // then
    assertThat(page.posts()).isEmpty();
    assertThat(page.nextCursor()).isEmpty();
  }
}
