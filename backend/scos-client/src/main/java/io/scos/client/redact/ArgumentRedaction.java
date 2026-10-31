package io.scos.client.redact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * argv의 문맥을 평문 규칙에 연결한다. 원소 안 공백·따옴표·셸 구분자는 같은 길이의 일반 글자로 바꾼 판정용 줄에서만 본다. 따라서 리터럴 인자가 셸 문법으로 이웃 인자를
 * 삼키지 않는다. 원소 하나만으로 판정한 결과와 비교해 이웃 문맥이 추가한 가림만 반영한다. 새로 비밀로 판정한 자리부터 그 인자 끝까지 보수적으로 가린다. 문자열 배열의
 * 용도는 구별하지 않으므로 안내문 뒤 문장도 비밀번호 값으로 가릴 수 있다.
 */
final class ArgumentRedaction {
  private static final Pattern BETWEEN_ARGUMENTS = Pattern.compile(" ");
  private final Redaction text;

  ArgumentRedaction(Redaction text) {
    this.text = text;
  }

  List<String> apply(List<String> arguments) {
    List<String> shapes = arguments.stream().map(ArgumentRedaction::shape).toList();
    String line = String.join(" ", shapes);
    String redacted = text.apply(line);
    if (redacted.equals(line)) {
      return arguments;
    }
    String[] parts = BETWEEN_ARGUMENTS.split(redacted, -1);
    // 규칙이 인자 경계까지 가렸다면 일부 원문을 잘못 되돌리지 않는다.
    if (parts.length != arguments.size()) {
      return Collections.nCopies(arguments.size(), Redaction.MASK);
    }
    var out = new ArrayList<String>(arguments.size());
    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      out.add(
          argument.isEmpty() || parts[i].equals(text.apply(shapes.get(i)))
              ? argument
              : maskedArgument(argument, parts[i]));
    }
    return List.copyOf(out);
  }

  private static String maskedArgument(String argument, String redacted) {
    int start = redacted.indexOf(Redaction.MASK);
    // 형상은 길이를 보존하고 SecretRule은 가림 전의 접두를 보존하므로 원문에서도 같은 위치다.
    return argument.substring(0, start) + Redaction.MASK;
  }

  private static String shape(String argument) {
    if (argument.isEmpty()) {
      return "_";
    }
    var out = new StringBuilder(argument.length());
    for (int i = 0; i < argument.length(); i++) {
      char c = argument.charAt(i);
      boolean literal =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || "-.:/=@+".indexOf(c) >= 0;
      out.append(literal ? c : '_');
    }
    return out.toString();
  }
}
