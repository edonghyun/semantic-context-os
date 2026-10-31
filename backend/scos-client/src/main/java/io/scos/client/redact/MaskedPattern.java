package io.scos.client.redact;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정규식 하나로 찾은 비밀을 가리는 규칙 조각. 패턴에 이름 붙은 그룹 {@value #SECRET}가 있으면 그 그룹만(앞뒤 키 이름·따옴표는 남긴다), 없으면 일치 전체를
 * 가린다. 그룹이 일치에 참여하지 않으면 그 일치는 건너뛴다.
 */
final class MaskedPattern implements SecretRule {
  static final String SECRET = "secret";

  private final Pattern pattern;
  private final boolean wholeMatch;

  private MaskedPattern(Pattern pattern, boolean wholeMatch) {
    this.pattern = pattern;
    this.wholeMatch = wholeMatch;
  }

  /** 그룹 {@value #SECRET}만 가린다. */
  static MaskedPattern secretGroup(String regex, int flags) {
    return new MaskedPattern(Pattern.compile(regex, flags), false);
  }

  /** 일치 전체를 가린다. */
  static MaskedPattern whole(String regex, int flags) {
    return new MaskedPattern(Pattern.compile(regex, flags), true);
  }

  @Override
  public String apply(String text) {
    Matcher m = pattern.matcher(text);
    if (!m.find()) {
      return text;
    }
    var masker = new Masker(text);
    do {
      if (wholeMatch) {
        masker.mask(m.start(), m.end());
      } else {
        masker.mask(m.start(SECRET), m.end(SECRET));
      }
    } while (m.find());
    return masker.result();
  }
}
