package io.scos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 소스 하나의 메서드·생성자 본문 길이: 본문 중괄호 사이에서 코드가 있는 줄 수(빈 줄·주석만 있는 줄은 세지 않는다). 열쇠는 "감싼 타입.이름(매개변수 타입)"이고 감싼
 * 타입은 가장 안쪽의 이름 있는 타입이다 — 익명 클래스의 메서드는 바깥 타입에 속하고, 같은 열쇠가 다시 나오면 "#2"부터 붙인다. 본문을 여는 중괄호 앞이 `)`(또는
 * `) throws …`)이고 괄호 앞 이름이 제어문·`new`·record 머리·enum 상수가 아닌 것을 메서드로 본다. 레코드 압축 생성자는 세지 않는다.
 */
final class MethodLengths {
  static final Pattern TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)");

  static final Set<String> NOT_METHODS =
      Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "return", "throw");

  static final Pattern THROWS = Pattern.compile("\\)\\s*throws\\s+[\\w.<>?,\\s]+$");

  static final Pattern NAME = Pattern.compile("(\\w+)\\s*$");

  static final Pattern SKIPPED_PREFIX = Pattern.compile("(?:\\bnew|\\brecord|\\.)$");

  static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(?:\\s*\\([^)]*\\))?");

  /** 헤더를 찾을 때 여는 중괄호·괄호 앞에서 보는 글자 수(throws 목록·반환 타입이 이보다 길지 않다). */
  static final int WINDOW = 400;

  private record TypeRange(String kind, String name, int open, int close) {}

  private final JavaSource java;
  private final String code;
  private final int[] lineStarts;
  private final List<TypeRange> types;

  private MethodLengths(String source) {
    this.java = JavaSource.of(source);
    this.code = java.code();
    this.lineStarts = lineStarts(code);
    this.types = typeRanges();
  }

  /** 열쇠 → 본문 코드 줄 수(소스 순서). */
  static Map<String, Integer> of(String source) {
    return new MethodLengths(source).measure();
  }

  private Map<String, Integer> measure() {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (int open = code.indexOf('{'); open >= 0; open = code.indexOf('{', open + 1)) {
      String signature = signatureOpenedAt(open);
      if (signature == null) continue;
      String key = signature;
      for (int n = 2; out.containsKey(key); n++) key = signature + "#" + n;
      out.put(key, codeLinesBetween(open, java.matchingBrace(open)));
    }
    return out;
  }

  /** 이 중괄호가 메서드·생성자 본문을 열면 그 열쇠, 아니면 null. */
  private String signatureOpenedAt(int open) {
    int closeParen = headerCloseParen(open);
    if (closeParen < 0) return null;
    int openParen = openingParen(closeParen);
    String head = window(openParen);
    Matcher name = NAME.matcher(head);
    if (!name.find()) return null;
    String method = name.group(1);
    String before = head.substring(0, name.start()).stripTrailing();
    if (NOT_METHODS.contains(method) || SKIPPED_PREFIX.matcher(before).find()) return null;
    TypeRange owner = enclosingType(open);
    if (owner.kind().equals("enum") && isEnumConstant(method, before)) return null;
    return owner.name()
        + "."
        + method
        + "("
        + parameterTypes(code.substring(openParen + 1, closeParen))
        + ")";
  }

  private int headerCloseParen(int open) {
    String head = window(open).stripTrailing();
    if (head.endsWith(")")) return open - (window(open).length() - head.length()) - 1;
    Matcher throwsClause = THROWS.matcher(head);
    if (!throwsClause.find()) return -1;
    return open - window(open).length() + throwsClause.start();
  }

  private String window(int end) {
    return code.substring(Math.max(0, end - WINDOW), end);
  }

  private int openingParen(int close) {
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      if (code.charAt(i) == ')') depth++;
      else if (code.charAt(i) == '(' && --depth == 0) return i;
    }
    throw new IllegalStateException("짝 없는 괄호: " + java.lineOf(close) + "줄");
  }

  /** enum 상수의 본문(`A(1) {`): enum 안에서 대문자 이름이 `{`·`,` 뒤에 온다. */
  private static boolean isEnumConstant(String name, String before) {
    return name.chars().noneMatch(Character::isLowerCase)
        && (before.endsWith("{") || before.endsWith(","));
  }

  /** "Map<String, T> m, final @Nullable String... xs" → "Map<String, T>, String...". */
  static String parameterTypes(String parameters) {
    List<String> types = new ArrayList<>();
    for (String parameter : splitTopLevel(parameters)) {
      String type =
          ANNOTATION
              .matcher(parameter)
              .replaceAll(" ")
              .replaceAll("\\bfinal\\b", " ")
              .replaceAll("\\s+", " ")
              .strip()
              .replaceAll("\\s*\\b\\w+$", "");
      if (!type.isEmpty()) types.add(type);
    }
    return String.join(", ", types);
  }

  private static List<String> splitTopLevel(String parameters) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < parameters.length(); i++) {
      char c = parameters.charAt(i);
      if (c == '<' || c == '(') depth++;
      else if (c == '>' || c == ')') depth--;
      else if (c == ',' && depth == 0) {
        parts.add(parameters.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(parameters.substring(start));
    return parts;
  }

  private TypeRange enclosingType(int offset) {
    return types.stream()
        .filter(t -> t.open() < offset && offset < t.close())
        .reduce((outer, inner) -> inner.open() > outer.open() ? inner : outer)
        .orElseThrow(() -> new IllegalStateException("타입 밖 본문: " + java.lineOf(offset) + "줄"));
  }

  private List<TypeRange> typeRanges() {
    List<TypeRange> ranges = new ArrayList<>();
    Matcher type = TYPE.matcher(code);
    while (type.find()) {
      int open = code.indexOf('{', type.end());
      if (open >= 0)
        ranges.add(new TypeRange(type.group(1), type.group(2), open, java.matchingBrace(open)));
    }
    return ranges;
  }

  private int codeLinesBetween(int open, int close) {
    int first = lineIndex(open) + 1;
    int last = lineIndex(close) - 1;
    int count = 0;
    for (int line = first; line <= last; line++) {
      int end = line + 1 < lineStarts.length ? lineStarts[line + 1] : code.length();
      if (!code.substring(lineStarts[line], end).isBlank()) count++;
    }
    return count;
  }

  private int lineIndex(int offset) {
    int found = Arrays.binarySearch(lineStarts, offset);
    return found >= 0 ? found : -found - 2;
  }

  private static int[] lineStarts(String code) {
    List<Integer> starts = new ArrayList<>(List.of(0));
    for (int i = 0; i < code.length(); i++) if (code.charAt(i) == '\n') starts.add(i + 1);
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }
}
