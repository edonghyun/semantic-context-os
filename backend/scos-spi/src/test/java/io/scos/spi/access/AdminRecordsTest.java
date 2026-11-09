package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("관리 페이지는 크기를 제한하고 전달된 항목을 보존한다")
class AdminRecordsTest {
  @Test
  @DisplayName("페이지 크기 0은 거부한다")
  void pageLimitRejectsZero() {
    // when·then
    assertThatThrownBy(() -> new PageRequest(0, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("페이지 크기 201은 거부한다")
  void pageLimitRejectsOverTwoHundred() {
    // when·then
    assertThatThrownBy(() -> new PageRequest(201, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("원본 목록이 변해도 페이지 항목은 변하지 않는다")
  void pageItemsAreImmutable() {
    var items = new ArrayList<>(List.of("alice"));
    var page = new Page<>(items, Optional.empty());

    items.add("bob");

    // then
    assertThat(page.items()).containsExactly("alice");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 50, 200})
  @DisplayName("허용 범위의 페이지 크기는 그대로 보존한다")
  void pageLimitAcceptsAllowedValues(int limit) {
    var page = new PageRequest(limit, Optional.of("opaque"));

    // then
    assertThat(page)
        .extracting(PageRequest::limit, PageRequest::after)
        .containsExactly(limit, Optional.of("opaque"));
  }

  @Test
  @DisplayName("기본 페이지는 첫 50개를 요청한다")
  void pageDefaultsToFifty() {
    var page = new PageRequest();

    // then
    assertThat(page).isEqualTo(new PageRequest(50, Optional.empty()));
  }

  @Test
  @DisplayName("페이지 항목을 직접 수정할 수 없다")
  void pageItemsRejectMutation() {
    var page = new Page<>(List.of("alice"), Optional.empty());

    // when·then
    assertThatThrownBy(() -> page.items().add("bob"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 201})
  @DisplayName("감사 조회에도 같은 페이지 크기 제한을 적용한다")
  void auditLimitRejectsOutOfRange(int limit) {
    // when·then
    assertThatThrownBy(
            () ->
                new AuditQuery(
                    limit,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 200})
  @DisplayName("감사 조회는 허용된 크기와 선택 필터를 보존한다")
  void auditQueryKeepsFilters(int limit) {
    var query =
        new AuditQuery(
            limit,
            Optional.of("opaque"),
            Optional.of("user.create"),
            Optional.of("alice"),
            Optional.of("user"),
            Optional.of("bob"));

    // then
    assertThat(query)
        .extracting(
            AuditQuery::limit,
            AuditQuery::after,
            AuditQuery::action,
            AuditQuery::actor,
            AuditQuery::targetType,
            AuditQuery::targetId)
        .containsExactly(
            limit,
            Optional.of("opaque"),
            Optional.of("user.create"),
            Optional.of("alice"),
            Optional.of("user"),
            Optional.of("bob"));
  }

  @Test
  @DisplayName("기본 감사 조회는 필터 없이 첫 50개를 요청한다")
  void auditDefaultsToFifty() {
    var query = new AuditQuery();

    // then
    assertThat(query)
        .isEqualTo(
            new AuditQuery(
                50,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("원본 경고 집합이 바뀌어도 신원 연결은 보존된다")
  void linkWarningsAreImmutable() {
    var warnings = new HashSet<>(Set.of("unobserved"));
    var link =
        new IdentityLink(
            new SourceInstanceId("slack-a"),
            "U01",
            "alice",
            LinkMethod.ADMIN,
            Instant.EPOCH,
            "alice",
            warnings);

    warnings.clear();

    // then
    assertThat(link.warnings()).containsExactly("unobserved");
  }

  @Test
  @DisplayName("원본 감사 상세가 바뀌어도 새 감사 값은 보존된다")
  void newAuditDetailIsImmutable() {
    var detail = new HashMap<>(Map.of("role", "member"));
    var event = new NewAuditEvent(Instant.EPOCH, "alice", "user.create", "user", "bob", detail);

    detail.clear();

    // then
    assertThat(event.detail()).containsExactlyEntriesOf(Map.of("role", "member"));
  }

  @Test
  @DisplayName("원본 감사 상세가 바뀌어도 저장 감사 값은 보존된다")
  void auditDetailIsImmutable() {
    var detail = new HashMap<>(Map.of("role", "member"));
    var event = new AuditEvent(1L, Instant.EPOCH, "alice", "user.create", "user", "bob", detail);

    detail.clear();

    // then
    assertThat(event.detail()).containsExactlyEntriesOf(Map.of("role", "member"));
  }
}
