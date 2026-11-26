package io.scos.boardarchive.projection;

import static org.assertj.core.api.Assertions.*;

import io.scos.boardarchive.store.PostStatus;
import io.scos.spi.projection.SourceDocument;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("보관소 투영은 변경 문서와 현존 집합을 독립적으로 반환한다")
class ArchiveProjectionTest {
  @TempDir Path dir;
  ProjectionScenario s;

  @BeforeEach
  void setup() {
    s = new ProjectionScenario(dir.resolve("archive.db"));
  }

  @AfterEach
  void close() {
    s.close();
  }

  @Test
  @DisplayName("문서 id는 사이트 접두어를 가진다")
  void documentIdIsSitePrefixed() {
    // given
    s.givenPost();
    // when
    var p = s.project();
    // then
    assertThat(p.documents())
        .extracting(SourceDocument::documentId)
        .containsExactly("example-board:42");
  }

  @Test
  @DisplayName("문서 수정 시각은 수집 시각이 아니라 글 작성 시각이다")
  void updatedAtIsPostCreationTime() {
    // given: 11-17에 쓴 글을 11-18에 수집했다
    s.givenPost();
    // when
    var p = s.project();
    // then
    assertThat(p.documents())
        .extracting(SourceDocument::updatedAt)
        .containsExactly("2026-11-17T00:00:00Z");
  }

  @Test
  @DisplayName("변경 없는 글은 전송하지 않고 현존 집합에 둔다")
  void unchangedPostIsNotResentButStaysLive() {
    // given
    s.givenPost();
    s.mark(s.project());
    // when
    var p = s.project();
    // then
    assertThat(p.documents()).isEmpty();
    assertThat(p.liveDocumentIds()).containsExactly("example-board:42");
  }

  @Test
  @DisplayName("댓글만 바뀌어도 문서를 다시 전송한다")
  void commentOnlyChangeIsResent() {
    // given
    s.givenPost();
    s.mark(s.project());
    s.comment();
    // when
    var p = s.project();
    // then
    assertThat(p.documents())
        .extracting(SourceDocument::body)
        .containsExactly("[General]\nBody\n\n— Reader (2026-11-17T00:01:00Z)\nReply");
  }

  @Test
  @DisplayName("게시판 이름이 바뀌면 다시 전송한다")
  void boardRenameIsResent() {
    // given
    s.givenPost();
    s.mark(s.project());
    s.renameBoard();
    // when
    var p = s.project();
    // then
    assertThat(p.documents()).extracting(SourceDocument::body).containsExactly("[Renamed]\nBody");
  }

  @Test
  @DisplayName("제외한 게시판 글은 현존 집합에서 빠진다")
  void excludedBoardLeavesLiveSet() {
    // given
    s.givenPost();
    // when
    var p = s.project(Set.of("b"), true);
    // then
    assertThat(p.liveDocumentIds()).isEmpty();
  }

  @Test
  @DisplayName("삭제한 글은 현존 집합에서 빠진다")
  void deletedPostLeavesLiveSet() {
    // given
    s.givenPost();
    s.status(PostStatus.DELETED);
    // when
    var p = s.project();
    // then
    assertThat(p.liveDocumentIds()).isEmpty();
  }

  @Test
  @DisplayName("확인 필요 글은 현존 집합에 남는다")
  void needsCheckStaysLive() {
    // given
    s.givenPost();
    s.status(PostStatus.NEEDS_CHECK);
    // when
    var p = s.project();
    // then
    assertThat(p.liveDocumentIds()).containsExactly("example-board:42");
  }

  @Test
  @DisplayName("대상에서 빠졌다가 돌아오면 다시 전송한다")
  void reenteringPostIsResent() {
    // given
    s.givenPost();
    s.mark(s.project());
    s.project(Set.of("b"), true);
    // when
    var p = s.project();
    // then
    assertThat(p.documents())
        .extracting(SourceDocument::documentId)
        .containsExactly("example-board:42");
  }

  @Test
  @DisplayName("읽은 해시 반영 후 새 revision은 다시 투영한다")
  void revisionWrittenAfterProjectionIsProjectedAgain() {
    // given
    s.givenPost();
    var first = s.project();
    s.edit();
    s.mark(first);
    // when
    var next = s.project();
    // then
    assertThat(next.documents())
        .extracting(SourceDocument::body)
        .containsExactly("[General]\nEdited");
  }

  @Test
  @DisplayName("현존 집합이 비게 되면 명시적 전체 투영을 요구한다")
  void emptyLiveSetIsRefusedWithoutFull() {
    // given
    s.givenPost();
    s.mark(s.project());
    // then
    assertThatThrownBy(() -> s.project(Set.of(), false))
        .isInstanceOf(LiveSetShrinkRefusedException.class)
        .hasMessage("Live set shrank from 1 to 0; full projection required");
  }

  @Test
  @DisplayName("20퍼센트 초과 축소를 거부한다")
  void shrinkOverTwentyPercentRefused() {
    // given
    s.givenFivePosts();
    s.project();
    s.status("1", PostStatus.DELETED);
    s.status("2", PostStatus.DELETED);
    // when·then
    assertThatThrownBy(() -> s.project())
        .isInstanceOf(LiveSetShrinkRefusedException.class)
        .hasMessage("Live set shrank from 5 to 3; full projection required");
  }

  @Test
  @DisplayName("정확히 20퍼센트 축소는 허용한다")
  void exactlyTwentyPercentShrinkIsAllowed() {
    // given
    s.givenFivePosts();
    s.project();
    s.status("1", PostStatus.DELETED);
    // when
    var p = s.project();
    // then
    assertThat(p.liveDocumentIds())
        .containsExactlyInAnyOrder(
            "example-board:2", "example-board:3", "example-board:4", "example-board:5");
  }

  @Test
  @DisplayName("전체 투영은 변경 여부와 관계없이 모두 전송한다")
  void fullResendsAll() {
    // given
    s.givenPost();
    s.mark(s.project());
    // when
    var p = s.project(Set.of("a"), true);
    // then
    assertThat(p.documents())
        .extracting(SourceDocument::documentId)
        .containsExactly("example-board:42");
  }

  @Test
  @DisplayName("일부 글이 대상에서 빠졌다 돌아오면 그 글만 재전송한다 (§6)")
  void partialExclusionOnlyClearsDepartedHash() {
    // given
    s.givenPost();
    s.givenPostInOtherBoard();
    s.mark(s.project(Set.of("a", "b"), true));
    s.project(Set.of("a"), true);
    // when
    var returning = s.project(Set.of("a", "b"), false);
    // then
    assertThat(returning.documents())
        .extracting(SourceDocument::documentId)
        .containsExactly("example-board:43");
  }
}
