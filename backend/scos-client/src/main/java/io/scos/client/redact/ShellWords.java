package io.scos.client.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 명령 이름 뒤의 셸 낱말들. 낱말은 공백·탭으로 갈리고, 짝이 맞는 따옴표 안(같은 줄, {@value #QUOTE_MAX}자 이하)의 공백·구분자는 낱말 안이다. 짝 없는
 * 따옴표는 보통 글자다. 명령 구분자(줄바꿈, 뒤가 공백이거나 끝인 `|` `;` `&`)에서 끝난다 — 낱말에 붙은 `pa|ss`는 비밀번호 글자일 수 있다. 평문에 든
 * 이스케이프한 문자열({@link Values})도 읽는다: 이스케이프한 탭(`\t`)은 공백, 이스케이프한 줄바꿈(`\n` `\r`)은 구분자, 이스케이프한
 * 큰따옴표(`\"`)는 역슬래시 수가 같은 따옴표와 짝이다. 한 번 훑기라 선형이다.
 */
final class ShellWords {
  /** 낱말 하나 {@code [start, end)}. */
  record Word(int start, int end) {}

  /** 낱말들과 명령이 끝난 자리. */
  record Command(List<Word> words, int end) {}

  /** 짝 따옴표를 찾는 거리 상한 — 짝 없는 따옴표가 많은 줄에서도 선형이게 묶는다. */
  private static final int QUOTE_MAX = 400;

  private ShellWords() {}

  // 위치 순회는 입력 길이로 묶고 next까지 이미 읽은 구간을 건너뛴다 — 전진 계산이 잘못되어도 무한 순회하지 않는다.
  /** {@code from}부터 명령 구분자까지의 낱말. */
  static Command after(String text, int from) {
    var words = new ArrayList<Word>();
    int next = from;
    for (var positions = IntStream.range(from, text.length()).iterator(); positions.hasNext(); ) {
      int i = positions.nextInt();
      if (i < next) continue;
      if (isSeparator(text, i)) return new Command(List.copyOf(words), i);
      int blank = blankLength(text, i);
      if (blank > 0) {
        next = i + blank;
        continue;
      }
      next = wordEnd(text, i);
      words.add(new Word(i, next));
    }
    return new Command(List.copyOf(words), text.length());
  }

  /** 낱말 전체가 따옴표 하나로 감싸였으면 그 안쪽 {@code [start, end)}. */
  static Optional<Word> quotedInside(String text, Word word) {
    int open = openerLength(text, word.start());
    if (open == 0 || closingQuote(text, word.start()) != word.end() - 1) {
      return Optional.empty();
    }
    return Optional.of(new Word(word.start() + open, word.end() - open));
  }

  /** {@code i}에서 여는 따옴표의 길이: `"`·`'`는 1, 이스케이프한 큰따옴표는 역슬래시 수 + 1, 따옴표가 아니면 0. */
  static int openerLength(String text, int i) {
    char c = text.charAt(i);
    if (c == '"' || c == '\'') {
      return 1;
    }
    int run = backslashRun(text, i);
    return run > 0 && i + run < text.length() && text.charAt(i + run) == '"' ? run + 1 : 0;
  }

  private static int wordEnd(String text, int from) {
    int next = from;
    for (var positions = IntStream.range(from, text.length()).iterator(); positions.hasNext(); ) {
      int i = positions.nextInt();
      if (i < next) continue;
      if (blankLength(text, i) > 0 || isSeparator(text, i)) return i;
      int close = openerLength(text, i) > 0 ? closingQuote(text, i) : -1;
      next = close < 0 ? i + Math.max(1, backslashRun(text, i)) : close + 1;
    }
    return text.length();
  }

  /**
   * {@code open}에서 연 따옴표의 짝(닫는 `"`·`'`의 자리). 맨 큰따옴표 안에서는 역슬래시가 다음 글자를 이스케이프하고, 이스케이프한 큰따옴표는 역슬래시 수가
   * 같은 따옴표가 짝이다. 줄이 바뀌거나 상한 안에 없으면 -1.
   */
  static int closingQuote(String text, int open) {
    int run = backslashRun(text, open);
    char quote = text.charAt(open + run);
    int limit = Math.min(text.length(), open + run + 1 + QUOTE_MAX);
    int next = open + run + 1;
    for (var positions = IntStream.range(next, limit).iterator(); positions.hasNext(); ) {
      int j = positions.nextInt();
      if (j < next) continue;
      char c = text.charAt(j);
      if (c == '\n' || c == '\r') {
        return -1;
      }
      if (c == '\\') {
        int inner = backslashRun(text, j);
        if (run > 0 && inner == run && j + inner < text.length() && text.charAt(j + inner) == '"') {
          return j + inner;
        }
        next = j + (run > 0 ? inner : 2);
      } else if (c == quote && run == 0) {
        return j;
      }
    }
    return -1;
  }

  private static int backslashRun(String text, int i) {
    int j = i;
    while (j < text.length() && text.charAt(j) == '\\') {
      j++;
    }
    return j - i;
  }

  /** {@code i}의 공백 길이: 공백·탭은 1, 이스케이프한 탭은 2, 아니면 0. */
  private static int blankLength(String text, int i) {
    char c = text.charAt(i);
    if (c == ' ' || c == '\t') {
      return 1;
    }
    return text.startsWith("\\t", i) ? 2 : 0;
  }

  /** 명령 구분자: 줄바꿈(이스케이프 포함), 그리고 뒤가 공백이거나 끝인 `|` `;` `&` `||` `&&`. 낱말에 붙은 `pa|ss`는 낱말 안이다. */
  private static boolean isSeparator(String text, int i) {
    char c = text.charAt(i);
    if (c == '|' || c == ';' || c == '&') {
      int after = i + 1 < text.length() && text.charAt(i + 1) == c && c != ';' ? i + 2 : i + 1;
      return after >= text.length() || isSpace(text, after);
    }
    return c == '\n' || c == '\r' || text.startsWith("\\n", i) || text.startsWith("\\r", i);
  }

  /** 공백·이스케이프한 공백. */
  private static boolean isSpace(String text, int i) {
    return Character.isWhitespace(text.charAt(i))
        || text.startsWith("\\n", i)
        || text.startsWith("\\r", i)
        || text.startsWith("\\t", i);
  }
}
