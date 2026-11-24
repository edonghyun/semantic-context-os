package io.scos.connector.tiro.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 폴더는 자기와 모든 조상이 전원 공개(`ALL_MEMBER_VIEWER`·`ALL_MEMBER_EDITOR`)일 때만 유효 공개다(Tiro 접근 설계 §5.2). 그 밖의
 * 값·없는 값은 비공개, 형식 위반·순환은 프로토콜 위반이다.
 */
@DisplayName("Tiro 폴더는 자기와 조상이 모두 전원 공개여야 공개다(§5.2)")
class TiroFoldersTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static TiroFolders read(String body) {
    return TiroFolders.read(JSON.readTree(body));
  }

  private static String folder(String id, String parentId, String sharingType) {
    return "{\"id\":\""
        + id
        + "\",\"parentId\":"
        + (parentId == null ? "null" : "\"" + parentId + "\"")
        + ",\"sharingType\":\""
        + sharingType
        + "\"}";
  }

  private static String listing(String... folders) {
    return "[" + String.join(",", folders) + "]";
  }

  @Test
  @DisplayName("ALL_MEMBER_VIEWER 최상위 폴더는 공개다")
  void allMemberFolderIsPublic() {
    // when
    TiroFolders folders = read(listing(folder("1", null, "ALL_MEMBER_VIEWER")));

    // then
    assertThat(folders.publicIds()).containsExactly("1");
  }

  @Test
  @DisplayName("ALL_MEMBER_EDITOR 폴더도 공개다")
  void editorSharingIsPublic() {
    // when
    TiroFolders folders = read(listing(folder("1", null, "ALL_MEMBER_EDITOR")));

    // then
    assertThat(folders.publicIds()).containsExactly("1");
  }

  @Test
  @DisplayName("PRIVATE·LIMITED 폴더는 비공개다")
  void privateAndLimitedAreNotPublic() {
    // when
    TiroFolders folders = read(listing(folder("1", null, "PRIVATE"), folder("2", null, "LIMITED")));

    // then
    assertThat(folders.privateIds()).containsExactlyInAnyOrder("1", "2");
  }

  @Test
  @DisplayName("모르는 sharingType과 sharingType 없음은 비공개다")
  void unknownSharingTypeIsPrivate() {
    // when
    TiroFolders folders =
        read(
            listing(
                folder("1", null, "FUTURE_VALUE"),
                "{\"id\":\"2\",\"parentId\":null}",
                "{\"id\":\"3\",\"sharingType\":7}"));

    // then
    assertThat(folders.privateIds()).containsExactlyInAnyOrder("1", "2", "3");
  }

  @Test
  @DisplayName("isTeamFolder는 공개 판정에 쓰지 않는다")
  void isTeamFolderIsIgnored() {
    // when
    TiroFolders folders =
        read(
            "[{\"id\":\"1\",\"parentId\":null,\"sharingType\":\"PRIVATE\",\"isTeamFolder\":true}]");

    // then
    assertThat(folders.privateIds()).containsExactly("1");
  }

  @Test
  @DisplayName("공개 부모 아래 PRIVATE 자식은 비공개이고 부모는 공개다")
  void privateChildOfPublicParentIsPrivate() {
    // when
    TiroFolders folders =
        read(listing(folder("1", null, "ALL_MEMBER_VIEWER"), folder("2", "1", "PRIVATE")));

    // then
    assertThat(folders.publicIds()).containsExactly("1");
    assertThat(folders.privateIds()).containsExactly("2");
  }

  @Test
  @DisplayName("비공개 부모 아래 ALL_MEMBER 자식은 비공개다")
  void publicChildOfPrivateParentIsPrivate() {
    // when
    TiroFolders folders =
        read(listing(folder("1", null, "PRIVATE"), folder("2", "1", "ALL_MEMBER_VIEWER")));

    // then
    assertThat(folders.privateIds()).containsExactlyInAnyOrder("1", "2");
  }

  @Test
  @DisplayName("손자 폴더는 조상 중 하나라도 비공개면 비공개다")
  void grandchildNeedsEveryAncestorPublic() {
    // when
    TiroFolders folders =
        read(
            listing(
                folder("1", null, "ALL_MEMBER_VIEWER"),
                folder("2", "1", "PRIVATE"),
                folder("3", "2", "ALL_MEMBER_VIEWER")));

    // then
    assertThat(folders.publicIds()).containsExactly("1");
  }

  @Test
  @DisplayName("부모가 목록에 없는 폴더는 자기 공유 값만 본다")
  void orphanUsesOwnSharingOnly() {
    // when
    TiroFolders folders = read(listing(folder("1", "99", "ALL_MEMBER_VIEWER")));

    // then
    assertThat(folders.publicIds()).containsExactly("1");
  }

  @Test
  @DisplayName("부모 사슬이 순환하면 프로토콜 위반이다")
  void cycleIsProtocolViolation() {
    // given
    String body = listing(folder("1", "2", "ALL_MEMBER_VIEWER"), folder("2", "1", "PRIVATE"));

    // when·then
    assertThatThrownBy(() -> read(body))
        .isInstanceOf(TiroProtocolException.class)
        .hasMessage("Tiro folder tree has a cycle");
  }

  @Test
  @DisplayName("숫자 id와 부모 id는 문자열로 읽는다")
  void numericIdsAreReadAsStrings() {
    // when
    TiroFolders folders =
        read(
            "[{\"id\":620940,\"parentId\":null,\"sharingType\":\"ALL_MEMBER_VIEWER\"},"
                + "{\"id\":630231,\"parentId\":620940,\"sharingType\":\"PRIVATE\"}]");

    // then
    assertThat(folders.publicIds()).containsExactly("620940");
    assertThat(folders.privateIds()).containsExactly("630231");
  }

  @ParameterizedTest(name = "목록 {0}")
  @ValueSource(
      strings = {
        "[{\"parentId\":null,\"sharingType\":\"PRIVATE\"}]",
        "[{\"id\":\"\",\"sharingType\":\"PRIVATE\"}]",
        "[{\"id\":1.5,\"sharingType\":\"PRIVATE\"}]",
        "[{\"id\":{},\"sharingType\":\"PRIVATE\"}]",
        "[{\"id\":\"1\",\"parentId\":true,\"sharingType\":\"PRIVATE\"}]",
        "[{\"id\":\"1\",\"sharingType\":\"PRIVATE\"},{\"id\":1,\"sharingType\":\"PRIVATE\"}]"
      })
  @DisplayName("id·parentId 형식이 틀리거나 id가 겹치면 프로토콜 위반이다")
  void malformedIdIsProtocolViolation(String body) {
    // when·then
    assertThatThrownBy(() -> read(body))
        .isInstanceOf(TiroProtocolException.class)
        .hasMessage("Invalid Tiro folder listing");
  }

  @Test
  @DisplayName("배열이 아닌 목록은 프로토콜 위반이다")
  void nonArrayIsProtocolViolation() {
    // when·then
    assertThatThrownBy(() -> read("{}"))
        .isInstanceOf(TiroProtocolException.class)
        .hasMessage("Invalid Tiro folder listing");
  }
}
