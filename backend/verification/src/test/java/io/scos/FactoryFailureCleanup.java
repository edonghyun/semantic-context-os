package io.scos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 색인 소유권을 넘기는 두 팩토리의 정리 관용구: 지역 변수로 연 직후 try로 조립을 감싸고, RuntimeException·Error catch의 본문은 같은 변수의
 * close와 재던지기로만 구성한다. 이 좁은 소스 구조를 벗어나면 위반이다. 일반적인 제어 흐름·별칭 분석이나 실제 핸들 해제의 증명은 아니다.
 */
final class FactoryFailureCleanup {
  private static final Pattern OPEN =
      Pattern.compile("\\b(?:SqliteIndex|LocalEngine)\\s*\\.\\s*open\\s*\\(");
  private static final Pattern ASSIGNMENT =
      Pattern.compile("\\b(?:SqliteIndex|LocalEngine|var)\\s+(\\w+)\\s*=\\s*$");
  private static final Pattern TRY = Pattern.compile("\\s*;\\s*try\\s*\\{");
  private static final Pattern CATCH =
      Pattern.compile(
          "\\s*catch\\s*\\(\\s*(?:RuntimeException\\s*\\|\\s*Error|Error\\s*\\|\\s*RuntimeException)"
              + "\\s+(\\w+)\\s*\\)\\s*\\{");

  private FactoryFailureCleanup() {}

  static List<Integer> unguardedOpenings(String source) {
    JavaSource java = JavaSource.of(source);
    List<Integer> unguarded = new ArrayList<>();
    Matcher opening = OPEN.matcher(java.code());
    while (opening.find()) {
      if (!hasFailureCleanup(java, opening)) unguarded.add(java.lineOf(opening.start()));
    }
    return unguarded;
  }

  private static boolean hasFailureCleanup(JavaSource java, Matcher opening) {
    Matcher assignment = ASSIGNMENT.matcher(java.code().substring(0, opening.start()));
    if (!assignment.find()) return false;
    int argumentsEnd = java.closingParen(opening.end() - 1);
    Matcher guarded = TRY.matcher(java.code()).region(argumentsEnd + 1, java.code().length());
    if (!guarded.lookingAt()) return false;
    int tryEnd = java.matchingBrace(guarded.end() - 1);
    Matcher caught = CATCH.matcher(java.code()).region(tryEnd + 1, java.code().length());
    if (!caught.lookingAt()) return false;
    String body = java.code().substring(caught.end(), java.matchingBrace(caught.end() - 1));
    return body.matches(
        "\\s*"
            + Pattern.quote(assignment.group(1))
            + "\\s*\\.\\s*close\\s*\\(\\s*\\)\\s*;\\s*throw\\s+"
            + Pattern.quote(caught.group(1))
            + "\\s*;\\s*");
  }
}
