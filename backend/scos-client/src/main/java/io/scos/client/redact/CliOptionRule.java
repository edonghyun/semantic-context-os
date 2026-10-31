package io.scos.client.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 이름으로 비밀을 받는 CLI 옵션의 값: `--password X`·`--token "a b"`, `sshpass -p X`, `-u`·`--user`의
 * `사용자:비밀번호`(사용자는 남긴다). 값이 또 다른 옵션(`--verbose`)이면 값이 아니다. `--password-stdin`·`--token-file`은 이름 뒤가
 * 공백이 아니라 걸리지 않는다.
 */
final class CliOptionRule implements SecretRule {
  /** 옵션과 값 사이 공백(같은 줄). */
  private static final String SP = "[ \\t]++";

  /** 값을 공백으로 받는 비밀 옵션 이름. */
  private static final String SECRET_FLAG =
      Values.DASH_BOUNDARY
          + "--(?:password|passwd|pass|pwd|token|api[_-]?key|client[_-]?secret|secret(?:[_-]?key)?"
          + "|access[_-]?token|auth[_-]?token)"
          + SP;

  private static final String SSHPASS = Values.DASH_BOUNDARY + "sshpass" + SP + "-p[ \\t]*+";

  /** `-u`·`--user`·`--user=` 뒤. */
  private static final String USER_OPTION =
      Values.DASH_BOUNDARY + "(?:-u" + SP + "|--user(?:=|" + SP + "))";

  /** uid:gid 숫자 쌍(`docker run -u 1000:1000`)은 사용자:비밀번호가 아니다. 사용자만 숫자(`12345:secret`)면 비밀번호를 가린다. */
  private static final String NOT_UID_GID = "(?!\\d++:\\d++(?![A-Za-z0-9_]))";

  /** 사용자 상한 — 따옴표 안 사용자에는 공백이 올 수 있어 길이로 묶는다. */
  private static final int USER_MAX = 100;

  private static final SecretRule RULE = new GuardedRule(text -> text.indexOf('-') >= 0, rules());

  private static List<SecretRule> rules() {
    var all = new ArrayList<SecretRule>();
    for (String option : List.of(SECRET_FLAG, SSHPASS)) {
      all.add(MaskedPattern.secretGroup(option + Values.DOUBLE_QUOTED, Pattern.CASE_INSENSITIVE));
      all.add(MaskedPattern.secretGroup(option + Values.ESCAPED_QUOTED, Pattern.CASE_INSENSITIVE));
      all.add(MaskedPattern.secretGroup(option + Values.SINGLE_QUOTED, Pattern.CASE_INSENSITIVE));
      all.add(
          MaskedPattern.secretGroup(
              option + Values.GLUED_AFTER_MASKED_QUOTE, Pattern.CASE_INSENSITIVE));
      all.add(
          MaskedPattern.secretGroup(option + "(?!-)" + Values.UNQUOTED, Pattern.CASE_INSENSITIVE));
    }
    all.addAll(userPasswordRules());
    return List.copyOf(all);
  }

  /** `-u`·`--user`의 `사용자:비밀번호`: 사용자는 남기고 비밀번호만 가린다. */
  private static List<SecretRule> userPasswordRules() {
    var all = new ArrayList<SecretRule>();
    // 따옴표로 감싼 "사용자:비밀번호"(사용자에 공백이 있어도) — 사용자와 따옴표는 남긴다.
    all.add(
        MaskedPattern.secretGroup(
            USER_OPTION
                + Values.ESCAPED_OPEN
                + NOT_UID_GID
                + "[^:\"\\\\\\r\\n]{1,"
                + USER_MAX
                + "}+:(?<secret>"
                + Values.ESCAPED_CONTENT
                + "{1,400}+)(?=\\k<q>\")",
            0));
    all.add(
        MaskedPattern.secretGroup(
            USER_OPTION
                + "\""
                + NOT_UID_GID
                + "[^:\"\\\\\\r\\n]{1,"
                + USER_MAX
                + "}+:(?<secret>(?:[^\"\\\\\\r\\n]|\\\\.?){1,400}+)(?=\")",
            0));
    // 사용자는 맨 글자, 비밀번호만 따옴표인 사용자:"비밀번호".
    String bareUser = USER_OPTION + NOT_UID_GID + "[^\\s:=\"'\\\\]++:";
    all.add(MaskedPattern.secretGroup(bareUser + Values.DOUBLE_QUOTED, 0));
    all.add(MaskedPattern.secretGroup(bareUser + Values.ESCAPED_QUOTED, 0));
    all.add(MaskedPattern.secretGroup(bareUser + Values.SINGLE_QUOTED, 0));
    all.add(
        MaskedPattern.secretGroup(
            USER_OPTION
                + "(?:\\\\*+\")?+"
                + NOT_UID_GID
                + "[^\\s:=\"\\\\]++:(?![\"\\\\])(?<secret>"
                + Values.VALUE_CHARS
                + ")",
            0));
    return List.copyOf(all);
  }

  @Override
  public String apply(String text) {
    return RULE.apply(text);
  }
}
