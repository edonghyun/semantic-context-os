package io.scos.client.redact;

import io.scos.client.redact.ShellWords.Word;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 특정 명령의 인자로 든 비밀번호. 같은 옵션이 되풀이돼도 모두 가리고, 따옴표 인자 안의 옵션 모양은 인자가 아니다.
 *
 * <ul>
 *   <li>`redis-cli … -a X`, `az login … -p X`, `docker login … -p X`: 옵션 다음 낱말. `-p`가 포트인 도구가 많아 이
 *       명령 안에서만 본다.
 *   <li>mysql 계열 `-p<비밀번호>`(붙여 쓴 것만 — `-p` 뒤가 공백이면 프롬프트다). 값이 `3306:3306` 같은 포트 짝이면 비밀번호가 아니다.
 * </ul>
 */
final class CommandPasswordRule implements SecretRule {
  /** 명령 이름 뒤에 이어 붙은 글자가 없어야 한다(`mysqlx`, `redis-cli.conf`는 다른 낱말). */
  private static final String NAME_END = "(?![A-Za-z0-9_.-])";

  /** 옵션 다음 낱말이 비밀번호인 명령. {@code needle}은 이름에 반드시 든 글자(소문자)다 — 없는 줄은 정규식을 돌리지 않는다. */
  private record OptionCommand(Pattern name, String needle, String option) {
    /** 옵션 바로 다음 낱말을 가린다. 다음 낱말이 또 다른 옵션(`-`로 시작)이면 값이 아니다. */
    void maskValues(String text, List<Word> words, Masker masker) {
      for (int i = 0; i + 1 < words.size(); i++) {
        Word flag = words.get(i);
        Word value = words.get(i + 1);
        if (text.substring(flag.start(), flag.end()).equalsIgnoreCase(option)
            && text.charAt(value.start()) != '-') {
          maskWord(text, value, masker);
        }
      }
    }
  }

  private static final List<OptionCommand> OPTION_COMMANDS =
      List.of(
          optionCommand("redis-cli", "redis-cli", "-a"),
          optionCommand("az[ \\t]++login", "login", "-p"),
          optionCommand("docker[ \\t]++login", "login", "-p"));

  /**
   * mysql이 명령이 아니라 이름을 값으로 받는 옵션의 값인 경우(`docker run --name mysql`)를 거른다. 값을 받지 않는 옵션 뒤(`sudo -E
   * mysql`)의 mysql은 명령이다.
   */
  private static final Pattern MYSQL =
      Pattern.compile(
          Values.DASH_BOUNDARY
              + "(?<!(?:--(?:name|hostname|link|network|net|container-name|service"
              + "|add-host|pod|namespace)|-[hn])[ \\t])"
              + "(?:mysql(?:dump|admin|import|check|show|slap|pump)?|mariadb(?:-dump)?)"
              + NAME_END);

  /** 짝 없는 홑따옴표 값을 가리는 길이 상한({@link Values}의 따옴표 값 상한과 같다). */
  private static final int UNTERMINATED_MAX = 400;

  private static final Pattern PORT_PAIR = Pattern.compile("\\d++(?::\\d++)++");

  /** 따옴표 없는 비밀번호 낱말에서 값으로 보는 앞부분(낱말에 붙은 닫는 따옴표·이스케이프한 공백 앞까지). */
  private static final Pattern UNQUOTED_VALUE = Pattern.compile(Values.VALUE_CHARS);

  private static OptionCommand optionCommand(String name, String needle, String option) {
    return new OptionCommand(
        Pattern.compile(Values.DASH_BOUNDARY + name + NAME_END, Pattern.CASE_INSENSITIVE),
        needle,
        option);
  }

  /** 명령 하나의 낱말에서 비밀번호 낱말을 골라 가린다. */
  @FunctionalInterface
  private interface Arguments {
    void mask(String text, List<Word> words, Masker masker);
  }

  @Override
  public String apply(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    String out = text;
    for (OptionCommand command : OPTION_COMMANDS) {
      if (lower.contains(command.needle())) {
        out = maskCommands(out, command.name(), command::maskValues);
      }
    }
    if (out.contains("mysql") || out.contains("mariadb")) {
      out = maskCommands(out, MYSQL, CommandPasswordRule::maskMysqlPasswords);
    }
    return out;
  }

  /**
   * 명령 이름마다 그 뒤 낱말을 {@code arguments}로 가린다. 한 명령을 다 훑은 자리부터 다음 명령 이름을 찾으므로 이름이 되풀이돼도 같은 구간을 다시 훑지
   * 않는다.
   */
  private static String maskCommands(String text, Pattern name, Arguments arguments) {
    Matcher m = name.matcher(text);
    if (!m.find()) {
      return text;
    }
    var masker = new Masker(text);
    int from;
    do {
      ShellWords.Command command = ShellWords.after(text, m.end());
      arguments.mask(text, command.words(), masker);
      from = command.end();
    } while (from < text.length() && m.find(from));
    return masker.result();
  }

  private static void maskMysqlPasswords(String text, List<Word> words, Masker masker) {
    for (Word word : words) {
      if (word.end() - word.start() > 2 && text.startsWith("-p", word.start())) {
        var password = new Word(word.start() + 2, word.end());
        if (!PORT_PAIR.matcher(text).region(password.start(), password.end()).matches()) {
          maskWord(text, password, masker);
        }
      }
    }
  }

  /** 따옴표 낱말은 따옴표 안만, 아니면 값 모양인 앞부분({@link Values#VALUE_CHARS})을 가린다. 짝 없는 여는 따옴표는 남긴다. */
  private static void maskWord(String text, Word word, Masker masker) {
    Optional<Word> inside = ShellWords.quotedInside(text, word);
    if (inside.isPresent()) {
      masker.mask(inside.get().start(), inside.get().end());
      return;
    }
    int open = ShellWords.openerLength(text, word.start());
    int start = word.start() + open;
    if (open > 0 && ShellWords.closingQuote(text, word.start()) >= 0) {
      // 따옴표 조각 뒤에 글자가 붙은 낱말(`'a b'cd`)은 셸에서 한 낱말이다 — 낱말 끝까지 가린다.
      masker.mask(start, word.end());
      return;
    }
    if (open == 1 && text.charAt(word.start()) == '\'') {
      // 짝 없는 홑따옴표 값은 공백을 넘어 이어질 수 있다(`-p'a b`) — 감싼 문자열이 끝나는 곳을 알 수 없어 텍스트 끝까지(상한 안) 가린다.
      masker.mask(start, Math.min(text.length(), start + UNTERMINATED_MAX));
      return;
    }
    Matcher value = UNQUOTED_VALUE.matcher(text).region(start, word.end());
    if (value.lookingAt()) {
      masker.mask(start, value.end());
    }
  }
}
