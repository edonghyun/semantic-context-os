package io.scos.engine.support.call;

import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * 커서 페이지네이션 한 번의 걷기에서 이미 요청한 커서들(첫 페이지는 "커서 없음"으로 센다). 받은 다음 커서가 그 가운데 하나면 공급자가 커서를 되돌린 것이다 — 바로 앞
 * 커서(A → A)뿐 아니라 돌아오는 순환(A → B → A)도. 그 페이지를 쓰기 전에 위반으로 끝내야 끝없이 같은 페이지를 걷지 않는다. 위반을 어떤 예외로 알릴지는
 * 커넥터가 정한다 — 그 메시지에 {@link #cycle}의 설명을 붙인다. 걷기 하나(목록 하나, 채널 하나의 history, 스레드 하나)마다 새로 만든다.
 */
public final class CursorTrail {
  private static final String FIRST_PAGE = "";
  private static final String NO_CURSOR = "-";
  private static final int FINGERPRINT_LENGTH = 8;
  private static final int HEAD = 2;
  private static final int TAIL = 9;

  private final SequencedSet<String> requested = new LinkedHashSet<>();

  /** 이 커서로 요청한다. 재시도로 같은 요청을 다시 보내도 한 번으로 센다. */
  public void request(Optional<String> cursor) {
    requested.add(cursor.orElse(FIRST_PAGE));
  }

  /** 받은 다음 커서가 이 걷기에서 이미 요청한 커서인가. */
  public boolean revisits(Optional<String> next) {
    return next.filter(requested::contains).isPresent();
  }

  /**
   * 위반 메시지에 붙일 걷기 설명: {@code cursor cycle: list=<목록> trail=[<요청 순서>] next=<다음 커서> length=<요청 수>}.
   * 커서는 불투명하고 원문을 남기지 않으므로 sha256 앞 8자 지문으로 싣는다(첫 페이지·커서 없음은 {@code -}). 긴 걷기는 앞 둘과 뒤 아홉만 싣고 가운데는
   * {@code …}로 줄인다 — 오류 요약의 길이 상한 안에 다음 커서까지 들어가게.
   */
  public String cycle(String list, Optional<String> next) {
    List<String> shown = new ArrayList<>();
    List<String> all = requested.stream().map(CursorTrail::fingerprint).toList();
    if (all.size() <= HEAD + TAIL) shown.addAll(all);
    else {
      shown.addAll(all.subList(0, HEAD));
      shown.add("…");
      shown.addAll(all.subList(all.size() - TAIL, all.size()));
    }
    return "cursor cycle: list="
        + list
        + " trail=["
        + String.join(",", shown)
        + "] next="
        + fingerprint(next.orElse(FIRST_PAGE))
        + " length="
        + all.size();
  }

  private static String fingerprint(String cursor) {
    return cursor.equals(FIRST_PAGE)
        ? NO_CURSOR
        : Text.sha256Hex(cursor).substring(0, FINGERPRINT_LENGTH);
  }
}
