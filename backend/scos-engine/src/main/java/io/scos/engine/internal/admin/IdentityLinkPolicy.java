package io.scos.engine.internal.admin;

import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore.Session;
import io.scos.spi.access.DomainPolicy;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 자동 일치에는 조직 허용 목록만 쓴다. 인스턴스 도메인은 읽기 키의 별도 조건이다. */
final class IdentityLinkPolicy {
  private final Set<String> organizationDomains;

  IdentityLinkPolicy(DomainPolicy domains) {
    organizationDomains = domains.orgDomains();
  }

  Optional<UserAccount> matchingUser(
      SourceAccount source, ObservedIdentity observed, Session session) {
    var identity = observed.identity();
    if (identity.guest() || identity.deleted()) return Optional.empty();
    if (!identity.emailVerified() && !source.kind().equals("tiro")) return Optional.empty();
    return identity
        .email()
        .map(email -> email.strip().toLowerCase(Locale.ROOT))
        .filter(this::hasOrganizationDomain)
        .flatMap(session::userByEmail)
        .filter(UserAccount::active);
  }

  private boolean hasOrganizationDomain(String email) {
    int separator = email.indexOf('@');
    return separator > 0
        && separator == email.lastIndexOf('@')
        && organizationDomains.contains(email.substring(separator + 1));
  }
}
