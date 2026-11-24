package io.scos.boardarchive.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Base contract for drivers that deliberately expose only the first page. */
@DisplayName("첫 쪽 전용 드라이버는 다음 커서 없이 끝난다 (BoardDriver)")
public abstract class SinglePageBoardDriverContract extends BoardDriverContract {
  @Test
  @DisplayName("첫 쪽 전용 목록에는 다음 커서가 없다")
  protected final void firstPageHasNoContinuation() {
    // when
    var page = scenario().firstPage("a");
    // then
    assertThat(page.nextCursor()).isEmpty();
  }
}
