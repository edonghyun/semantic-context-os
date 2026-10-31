package io.scos.engine.support.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("커서 걷기는 이미 요청한 커서로 돌아오는 다음 커서를 알아본다")
class CursorTrailTest {
  @Test
  @DisplayName("바로 앞에 요청한 커서가 다음 커서로 오면 되돌아온 것이다")
  void sameCursorRevisits() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.of("a"));

    // when·then
    assertThat(trail.revisits(Optional.of("a"))).isTrue();
  }

  @Test
  @DisplayName("앞서 요청한 커서로 돌아오는 순환(A → B → A)도 되돌아온 것이다")
  void cycleRevisits() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.of("a"));
    trail.request(Optional.of("b"));

    // when·then
    assertThat(trail.revisits(Optional.of("a"))).isTrue();
  }

  @Test
  @DisplayName("처음 보는 커서와 커서 없음(마지막 페이지)은 되돌아온 것이 아니다")
  void newCursorAdvances() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.empty());
    trail.request(Optional.of("a"));

    // when·then
    assertThat(List.of(trail.revisits(Optional.of("b")), trail.revisits(Optional.empty())))
        .containsExactly(false, false);
  }

  @Test
  @DisplayName("순환 설명은 목록·요청 순서의 커서 지문(첫 페이지 -)·다음 커서 지문·걷기 길이를 싣는다")
  void cycleDescribesWalk() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.empty());
    trail.request(Optional.of("b"));
    trail.request(Optional.of("a"));

    // when
    String described = trail.cycle("blocks:page-1", Optional.of("b"));

    // then
    assertThat(described)
        .isEqualTo(
            "cursor cycle: list=blocks:page-1 trail=[-,3e23e816,ca978112] next=3e23e816 length=3");
  }

  @Test
  @DisplayName("재시도로 다시 보낸 요청은 자취에 한 번만 남는다")
  void retriedRequestAppearsOnce() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.of("a"));
    trail.request(Optional.of("a"));

    // when
    String described = trail.cycle("rows:ds-1:active", Optional.of("a"));

    // then
    assertThat(described)
        .isEqualTo("cursor cycle: list=rows:ds-1:active trail=[ca978112] next=ca978112 length=1");
  }

  @Test
  @DisplayName("긴 걷기는 앞 둘과 뒤 아홉만 싣고 가운데는 …로 줄인다(길이는 전체)")
  void longWalkElidesMiddle() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.empty());
    for (String cursor : List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"))
      trail.request(Optional.of(cursor));

    // when
    String described = trail.cycle("search:active", Optional.of("b"));

    // then
    assertThat(described)
        .isEqualTo(
            "cursor cycle: list=search:active trail=[-,ca978112,…,18ac3e73,3f79bb7b,252f10c8,"
                + "cd0aa985,aaa94026,de7d1b72,189f4003,8254c329,acac86c0] next=3e23e816 length=13");
  }

  @Test
  @DisplayName("다음 커서가 없음(첫 페이지 커서를 다시 요청)이면 next는 -다")
  void firstPageNextIsDash() {
    // given
    CursorTrail trail = new CursorTrail();
    trail.request(Optional.empty());

    // when
    String described = trail.cycle("notes", Optional.empty());

    // then
    assertThat(described).isEqualTo("cursor cycle: list=notes trail=[-] next=- length=1");
  }
}
