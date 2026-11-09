package io.scos.engine.internal.admin;

import java.util.Locale;
import java.util.Optional;

/** 관리 계정 생성 경로들이 공유하는 이메일 저장 규칙. */
final class AccountEmails {
  private AccountEmails() {}

  static Optional<String> normalize(String email) {
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    int separator = normalized.indexOf('@');
    if (separator <= 0 || separator == normalized.length() - 1) return Optional.empty();
    return Optional.of(normalized);
  }
}
