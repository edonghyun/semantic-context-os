package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 코드펜스 추적기(TS core/ingestion/code-fence.ts): 라인을 순서대로 소비하며 펜스 소속을 판정한다. CommonMark: ```·~~~ 마커(3칸
 * 이하 들여쓰기), 닫는 마커는 같은 문자·같은 길이 이상·정보 문자열 없음. 닫히지 않은 펜스는 끝까지 열린 채.
 */
final class CodeFenceTracker {
  // JS `.`은 U+0085를 포함하므로 DOTALL(라인에는 개행이 없다)
  private static final Pattern MARKER =
      Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$", Pattern.DOTALL);
  private String fence;

  /** 마커 라인을 포함해 펜스에 속한 라인이면 true. */
  boolean consume(String line) {
    Matcher m = MARKER.matcher(line);
    String marker = m.matches() ? m.group(1) : null;
    if (fence != null) {
      boolean closes =
          marker != null
              && marker.charAt(0) == fence.charAt(0)
              && marker.length() >= fence.length()
              && Text.jsTrim(m.group(2)).isEmpty();
      if (closes) fence = null;
      return true;
    }
    if (marker != null) {
      fence = marker;
      return true;
    }
    return false;
  }
}
