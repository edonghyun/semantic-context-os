package io.scos;

import java.util.ArrayList;
import java.util.List;

/** Java 소스 하나: 원문, 주석·문자열·문자·텍스트 블록을 공백으로 지운 코드(줄 위치 유지), 주석 위치 목록. 코드에서만 중괄호·괄호·어노테이션을 본다. */
record JavaSource(String text, String code, List<int[]> comments) {
  static JavaSource of(String text) {
    StringBuilder code = new StringBuilder(text);
    List<int[]> comments = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      int end;
      if (text.startsWith("//", i)) {
        end = text.indexOf('\n', i);
        if (end < 0) end = text.length();
        comments.add(new int[] {i, end});
      } else if (text.startsWith("/*", i)) {
        end = text.indexOf("*/", i + 2) + 2;
        comments.add(new int[] {i, end});
      } else if (text.startsWith("\"\"\"", i)) {
        end = text.indexOf("\"\"\"", i + 3) + 3;
      } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
        end = i + 1;
        while (text.charAt(end) != text.charAt(i)) end += text.charAt(end) == '\\' ? 2 : 1;
        end++;
      } else {
        i++;
        continue;
      }
      for (int k = i; k < end; k++) if (code.charAt(k) != '\n') code.setCharAt(k, ' ');
      i = end;
    }
    return new JavaSource(text, code.toString(), comments);
  }

  int matchingBrace(int open) {
    int depth = 0;
    for (int i = open; i < code.length(); i++) {
      if (code.charAt(i) == '{') depth++;
      else if (code.charAt(i) == '}' && --depth == 0) return i;
    }
    throw new IllegalStateException("닫히지 않은 중괄호: " + lineOf(open) + "줄");
  }

  int closingParen(int open) {
    int depth = 0;
    for (int i = open; i < code.length(); i++) {
      if (code.charAt(i) == '(') depth++;
      else if (code.charAt(i) == ')' && --depth == 0) return i;
    }
    throw new IllegalStateException("닫히지 않은 괄호: " + lineOf(open) + "줄");
  }

  /** from과 to 사이에서 시작하는 주석의 원문. */
  List<String> commentsBetween(int from, int to) {
    return comments.stream()
        .filter(c -> c[0] > from && c[0] < to)
        .map(c -> text.substring(c[0], c[1]))
        .toList();
  }

  int lineOf(int offset) {
    return (int) text.substring(0, offset).chars().filter(ch -> ch == '\n').count() + 1;
  }
}
