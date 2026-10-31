package io.scos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제품 소스의 {@code EventLog.<수준>(로거, 이름)} 호출. 이름은 문자열 리터럴이거나 같은 파일의 {@code static final String} 상수여야
 * 정할 수 있다(그래야 카탈로그와 대조할 수 있다).
 */
final class EventLogCalls {
  /** 이벤트 이름 형식(logging.md §2.1): 소문자 점 표기 세 마디, 마디 안의 단어는 밑줄로 잇는다. */
  static final Pattern NAME =
      Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:_[a-z0-9]+)*){2}");

  static final Pattern CALL =
      Pattern.compile("\\bEventLog\\s*\\.\\s*(debug|info|warn|error)\\s*\\(");

  /**
   * {@code EventLog} 멤버의 static import. 막는 이유: {@link #CALL}은 {@code EventLog.<수준>(}만 보므로 static
   * import로 부른 {@code warn(log, …)}은 이름·카탈로그 게이트를 빠져나간다.
   */
  static final Pattern STATIC_IMPORT =
      Pattern.compile(
          "\\bimport\\s+static\\s+io\\.scos\\.engine\\.(?:support\\.logging\\.)?EventLog\\s*\\.\\s*([\\w$*]+)\\s*;");

  /** 이스케이프 없는 문자열 리터럴(이벤트 이름에는 이스케이프가 필요 없다). */
  static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");

  static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

  private EventLogCalls() {}

  /** {@code EventLog} 멤버의 static import 하나. */
  record StaticImport(String file, int line, String member) {
    String where() {
      return file + ":" + line;
    }
  }

  /** 소스 하나의 {@code EventLog} 멤버 static import(주석 안은 보지 않는다). */
  static List<StaticImport> staticImports(String file, String source) {
    JavaSource java = JavaSource.of(source);
    List<StaticImport> imports = new ArrayList<>();
    Matcher imported = STATIC_IMPORT.matcher(java.code());
    while (imported.find())
      imports.add(new StaticImport(file, java.lineOf(imported.start()), imported.group(1)));
    return imports;
  }

  /** 호출 하나. 이름을 정하지 못하면 {@code name}이 비고 {@code argument}에 원문이 남는다. */
  record Call(String file, int line, String level, Optional<String> name, String argument) {
    String where() {
      return file + ":" + line;
    }
  }

  /** 소스 하나에서 찾은 호출(주석·문자열 안은 보지 않는다). */
  static List<Call> of(String file, String source) {
    JavaSource java = JavaSource.of(source);
    List<Call> calls = new ArrayList<>();
    Matcher call = CALL.matcher(java.code());
    while (call.find()) {
      int open = call.end() - 1;
      int close = java.closingParen(open);
      String argument = secondArgument(java, open, close);
      calls.add(
          new Call(
              file, java.lineOf(call.start()), call.group(1), name(source, argument), argument));
    }
    return calls;
  }

  static boolean isWellFormed(String name) {
    return NAME.matcher(name).matches();
  }

  /** 괄호 안 최상위 쉼표로 가른 둘째 인자의 원문(없으면 빈 문자열). */
  private static String secondArgument(JavaSource java, int open, int close) {
    List<Integer> commas = new ArrayList<>();
    int depth = 0;
    for (int i = open + 1; i < close; i++) {
      char c = java.code().charAt(i);
      if (c == '(' || c == '[' || c == '{') depth++;
      else if (c == ')' || c == ']' || c == '}') depth--;
      else if (c == ',' && depth == 0) commas.add(i);
    }
    if (commas.isEmpty()) return "";
    int end = commas.size() > 1 ? commas.get(1) : close;
    return java.text().substring(commas.get(0) + 1, end).strip();
  }

  private static Optional<String> name(String source, String argument) {
    Matcher literal = LITERAL.matcher(argument);
    if (literal.matches()) return Optional.of(literal.group(1));
    if (!IDENTIFIER.matcher(argument).matches()) return Optional.empty();
    Matcher constant =
        Pattern.compile(
                "\\bstatic\\s+final\\s+String\\s+"
                    + Pattern.quote(argument)
                    + "\\s*=\\s*\"([^\"\\\\]*)\"\\s*;")
            .matcher(source);
    return constant.find() ? Optional.of(constant.group(1)) : Optional.empty();
  }
}
