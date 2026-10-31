package io.scos.collection.internal;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * credentialRef `<scheme>:<reference>`의 모양(ADR-0022 §2). 스킴은 첫 `:` 앞이고 이름 모양은 `[a-z][a-z0-9-]*`다.
 * 참조는 토큰일 수 있어 {@link #toString}이 싣지 않는다.
 */
public record CredentialRef(String scheme, String reference) {
  private static final Pattern SCHEME_NAME = Pattern.compile("^[a-z][a-z0-9-]*$");

  /** 첫 `:` 앞이 스킴 이름 모양이면 나눈다. 아니면 empty — 접두사가 없거나 토큰·Basic "email:token"을 붙여 넣은 경우다. */
  public static Optional<CredentialRef> parse(String text) {
    int separator = text.indexOf(':');
    if (separator == -1) return Optional.empty();
    String scheme = text.substring(0, separator);
    if (!SCHEME_NAME.matcher(scheme).matches()) return Optional.empty();
    return Optional.of(new CredentialRef(scheme, text.substring(separator + 1)));
  }

  public boolean hasReference() {
    return !reference.isEmpty();
  }

  @Override
  public String toString() {
    return "CredentialRef[" + scheme + ":…]";
  }
}
