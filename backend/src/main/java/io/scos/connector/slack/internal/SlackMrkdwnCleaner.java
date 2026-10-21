package io.scos.connector.slack.internal;

import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack mrkdwn → 평문(TS source-projector/src/slack-mrkdwn-cleaner.ts). 코드펜스·인라인 코드는 불변. 펜스 상태는 메시지
 * 전체에서 줄을 넘어 이어진다.
 */
public final class SlackMrkdwnCleaner {
  private static final Pattern NEWLINE = Pattern.compile("\n");
  private static final Pattern FENCE = Pattern.compile("```");
  private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
  private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
  private static final String WS = Text.whitespaceClass();
  private static final String NOT_WS = "[^" + Text.whitespaceChars() + "]";
  private static final List<String[]> RULES =
      List.of(
          new String[] {"<@([A-Z0-9]+)\\|([^>]+)>", "@$2"},
          new String[] {"<@([A-Z0-9]+)>", "@$1"},
          new String[] {"<!subteam\\^[A-Z0-9]+\\|@?([^>]+)>", "@$1"},
          new String[] {"<!subteam\\^[A-Z0-9]+>", "@그룹"},
          new String[] {"<!(here|channel|everyone)>", "@$1"},
          new String[] {"<#([A-Z0-9]+)\\|([^>]+)>", "#$2"},
          new String[] {"<#([A-Z0-9]+)>", "#$1"},
          new String[] {"<(https?://[^|>]+)\\|([^>]+)>", "$2"},
          new String[] {"<(https?://[^>]+)>", "$1"},
          new String[] {
            "(^|["
                + Text.whitespaceChars()
                + "(])\\*("
                + NOT_WS
                + "(?:[^*\\n]*?"
                + NOT_WS
                + ")?)\\*(?=\\z|["
                + Text.whitespaceChars()
                + ").,!?:;])",
            "$1$2"
          },
          new String[] {
            "(^|["
                + Text.whitespaceChars()
                + "(])_("
                + NOT_WS
                + "(?:[^_\\n]*?"
                + NOT_WS
                + ")?)_(?=\\z|["
                + Text.whitespaceChars()
                + ").,!?:;])",
            "$1$2"
          },
          new String[] {
            "(^|["
                + Text.whitespaceChars()
                + "(])~("
                + NOT_WS
                + "(?:[^~\\n]*?"
                + NOT_WS
                + ")?)~(?=\\z|["
                + Text.whitespaceChars()
                + ").,!?:;])",
            "$1$2"
          },
          // 첫 글자는 영문자/언더스코어/+/- 허용(:+1: 등). 숫자만인 이름(:100:)은 시각 표기와 구분 불가해 미지원(TS와 같은 알려진 갭)
          new String[] {":[a-z_+\\-][a-z0-9_+\\-]*:", ""});
  private static final List<Pattern> COMPILED =
      RULES.stream().map(r -> Pattern.compile(r[0])).toList();

  public String clean(String text) {
    String[] lines = NEWLINE.split(text, -1);
    boolean inFence = false;
    List<String> out = new ArrayList<>(lines.length);
    for (String line : lines) {
      String[] parts = FENCE.split(line, -1);
      boolean fence = inFence;
      List<String> cleanedParts = new ArrayList<>(parts.length);
      for (int i = 0; i < parts.length; i++) {
        cleanedParts.add(fence ? parts[i] : transformOutsideFence(parts[i]));
        if (i < parts.length - 1) fence = !fence;
      }
      String joined = String.join("```", cleanedParts);
      // 줄이 펜스 안에서 끝나면(미닫힘) 트림도 하지 않는다 — 펜스 내부는 원문 그대로
      out.add(fence ? joined : Text.jsTrimEnd(joined));
      inFence = fence;
    }
    return String.join("\n", out);
  }

  /** 인라인 코드를 자리표시자로 가린 채 서식 정리·공백 축약을 적용하고 복원한다. */
  private static String transformOutsideFence(String segment) {
    List<String> codeSpans = new ArrayList<>();
    char placeholder = pickPlaceholder(segment);
    Matcher m = CODE_SPAN.matcher(segment);
    StringBuilder masked = new StringBuilder();
    while (m.find()) {
      codeSpans.add(m.group());
      m.appendReplacement(
          masked,
          Matcher.quoteReplacement(
              placeholder + Integer.toString(codeSpans.size() - 1) + placeholder));
    }
    m.appendTail(masked);
    String cleaned = MULTI_SPACE.matcher(cleanSegment(masked.toString())).replaceAll(" ");
    if (codeSpans.isEmpty()) return cleaned;
    Matcher r =
        Pattern.compile(
                Pattern.quote(String.valueOf(placeholder))
                    + "(\\d+)"
                    + Pattern.quote(String.valueOf(placeholder)))
            .matcher(cleaned);
    StringBuilder restored = new StringBuilder();
    while (r.find())
      r.appendReplacement(
          restored, Matcher.quoteReplacement(codeSpans.get(Integer.parseInt(r.group(1)))));
    r.appendTail(restored);
    return restored.toString();
  }

  /** 원문에 등장하지 않는 사설 영역(PUA) 문자 하나. */
  private static char pickPlaceholder(String segment) {
    for (int cp = 0xE000; cp <= 0xF8FF; cp++) if (segment.indexOf(cp) < 0) return (char) cp;
    throw new IllegalStateException("사설 영역 문자를 모두 소진해 자리표시자를 선택할 수 없습니다");
  }

  private static String cleanSegment(String seg) {
    String s = seg;
    for (int i = 0; i < COMPILED.size(); i++)
      s = COMPILED.get(i).matcher(s).replaceAll(RULES.get(i)[1]);
    return s;
  }

  static String whitespaceClass() {
    return WS;
  }
}
