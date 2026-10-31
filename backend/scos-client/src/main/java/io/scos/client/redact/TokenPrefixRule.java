package io.scos.client.redact;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 키 없이도 모양으로 알 수 있는 토큰(접두 + 몸통)을 통째로 가린다. 접두 하나가 표의 한 줄이다. 낱말 한가운데(`task-management`)는 건드리지 않는다. 몸통
 * 길이 하한은 같은 접두로 시작하는 평범한 낱말(`sk-learn`)을 거른다.
 */
final class TokenPrefixRule implements SecretRule {
  /** 접두(글자 그대로), 몸통 정규식, `-`도 낱말 글자로 보는가(접두 앞에 `-`가 붙어도 낱말 한가운데로 보는가). */
  private record Shape(String prefix, String body, boolean dashIsWordChar) {}

  private static final List<Shape> SHAPES =
      List.of(
          new Shape("eyJ", "[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_-]{5,}+\\.[A-Za-z0-9_-]{5,}+", true),
          new Shape("xox", "[abposr]-[A-Za-z0-9-]{10,}+", false),
          new Shape("sk-", "(?:ant-)?[A-Za-z0-9_-]{20,}+", false),
          new Shape("gh", "[pousr]_[A-Za-z0-9]{36,}+", false),
          new Shape("github_pat_", "[A-Za-z0-9_]{22,}+", false),
          new Shape("npm_", "[A-Za-z0-9]{36}", false),
          new Shape("glpat-", "[A-Za-z0-9_-]{20,}+", false),
          new Shape("AKIA", "[0-9A-Z]{16}", false),
          new Shape("AIza", "[0-9A-Za-z_-]{35}", false),
          new Shape("sk_", "(?:live|test)_[A-Za-z0-9]{16,}+", false),
          new Shape("rk_", "(?:live|test)_[A-Za-z0-9]{16,}+", false),
          new Shape("xapp-", "[0-9]-[A-Za-z0-9-]{10,}+", false),
          new Shape("ya29.", "[A-Za-z0-9_-]{20,}+", false),
          new Shape("GOCSPX-", "[A-Za-z0-9_-]{20,}+", false));

  private static final SecretRule TOKENS =
      MaskedPattern.whole(
          SHAPES.stream().map(TokenPrefixRule::regex).collect(Collectors.joining("|")), 0);

  private static String regex(Shape shape) {
    String boundary = shape.dashIsWordChar() ? Values.DASH_BOUNDARY : Values.BOUNDARY;
    return boundary + Pattern.quote(shape.prefix()) + shape.body();
  }

  @Override
  public String apply(String text) {
    for (Shape shape : SHAPES) {
      if (text.contains(shape.prefix())) {
        return TOKENS.apply(text);
      }
    }
    return text;
  }
}
