package io.scos.engine.internal.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.admin.AdminPort;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.store.Principal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리 조회는 현재 관리자 권한과 안정된 페이지 경계를 지킨다(§7)")
class AdminQueriesTest {
  private static final Principal ADMIN = new Principal("admin");
  private static final Principal MEMBER = new Principal("member");
  private final AdminScenario scenario = new AdminScenario();

  @BeforeEach
  void setUp() {
    scenario.givenUser("admin", "admin@example.com", true, Role.ADMIN);
    scenario.givenUser("member", "member@example.com", true, Role.MEMBER);
  }

  @Test
  @DisplayName("구성원은 사용자 목록을 볼 수 없다")
  void memberCannotListUsers() {
    // when
    var failure = catchThrowable(() -> scenario.port().users(MEMBER, new PageRequest()));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("구성원은 연결 목록을 볼 수 없다")
  void memberCannotListLinks() {
    // when
    var failure =
        catchThrowable(() -> scenario.port().links(MEMBER, Optional.empty(), new PageRequest()));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("구성원은 공개 범위 목록을 볼 수 없다")
  void memberCannotListScopes() {
    // when
    var failure = catchThrowable(() -> scenario.port().scopes(MEMBER));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("구성원은 감사를 볼 수 없다")
  void memberCannotReadAudit() {
    // when
    var failure = catchThrowable(() -> scenario.port().audit(MEMBER, new AuditQuery()));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("구성원은 원천 접근 상태를 볼 수 없다")
  void memberCannotReadSourceAccess() {
    // when
    var failure = catchThrowable(() -> scenario.port().sourceAccess(MEMBER));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("구성원은 문서 접근 원인을 볼 수 없다")
  void memberCannotExplainDocumentAccess() {
    // when
    var failure =
        catchThrowable(() -> scenario.port().explainAccess(MEMBER, "doc", Optional.of("member")));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("비활성 관리자는 감사를 볼 수 없다")
  void deactivatedAdminCannotReadAudit() {
    // given
    scenario.givenActive("admin", false);
    // when
    var failure = catchThrowable(() -> scenario.port().audit(ADMIN, new AuditQuery()));
    // then
    assertForbidden(failure);
  }

  @Test
  @DisplayName("같은 시각의 감사는 id 역순으로 중복 없이 이어진다")
  void auditPagesHaveStableOrderAtSameTimestamp() {
    // given
    scenario.port().createUser(ADMIN, "one@example.com", Role.MEMBER);
    scenario.port().createUser(ADMIN, "two@example.com", Role.MEMBER);
    scenario.port().createUser(ADMIN, "three@example.com", Role.MEMBER);
    // when
    var first = scenario.port().audit(ADMIN, query(Optional.empty()));
    var second = scenario.port().audit(ADMIN, query(first.nextCursor()));
    // then
    assertThat(first.items()).extracting(AuditEvent::id).containsExactly(3L, 2L);
    assertThat(second.items()).extracting(AuditEvent::id).containsExactly(1L);
    assertThat(second.nextCursor()).isEmpty();
  }

  @Test
  @DisplayName("잘못된 감사 커서는 invalid_request다")
  void invalidCursorIsRejected() {
    // when
    var failure = catchThrowable(() -> scenario.port().audit(ADMIN, query(Optional.of("broken"))));
    // then
    assertThat(failure).isInstanceOf(AdminPort.Failure.class).hasMessage("invalid_request");
  }

  @Test
  @DisplayName("감사 필터는 요청한 행동만 반환한다")
  void auditFiltersArePreserved() {
    // given
    scenario.port().createUser(ADMIN, "alice@example.com", Role.MEMBER);
    scenario.port().changeRole(ADMIN, "member", Role.ADMIN);
    // when
    var result =
        scenario
            .port()
            .audit(
                ADMIN,
                new AuditQuery(
                    50,
                    Optional.empty(),
                    Optional.of("user.role"),
                    Optional.of("admin"),
                    Optional.of("user"),
                    Optional.of("member")));
    // then
    assertThat(result.items())
        .singleElement()
        .extracting(AuditEvent::action, AuditEvent::actor, AuditEvent::targetId)
        .containsExactly("user.role", "admin", "member");
  }

  private AuditQuery query(Optional<String> cursor) {
    return new AuditQuery(
        2, cursor, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  private void assertForbidden(Throwable failure) {
    assertThat(failure).isInstanceOf(AdminPort.Failure.class).hasMessage("forbidden");
  }
}
