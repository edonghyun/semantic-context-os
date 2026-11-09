package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.StoredToken;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 메모리·PostgreSQL 관리 어댑터가 공유하는 저장 계약. 등록은 실제 소스 등록 경계를 사용한다. */
@DisplayName("관리 저장소는 식별자 유일성과 트랜잭션을 보장한다")
public abstract class AdminStoreContract {
  private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");
  private static final UserAccount ALICE =
      new UserAccount("alice", "alice@example.com", true, Role.ADMIN, AT);
  private static final UserAccount BOB =
      new UserAccount("bob", "bob@example.com", true, Role.MEMBER, AT);
  private static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  private static final SourceInstanceId MISSING = new SourceInstanceId("missing-a");
  private static final TokenHash HASH =
      new TokenHash("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  protected AdminStore store;

  protected abstract AdminStore newStore();

  /** 관리 저장소의 별도 seed가 아니라 수집 등록이 소유하는 id·kind를 갱신한다. */
  protected abstract void registerSource(SourceAccount source);

  protected abstract UserTokens authentication();

  protected abstract void seedAccessDirectory(AccessDirectory directory);

  protected abstract String serializeMetadata(Object metadata);

  protected abstract IdentityStore identityReader();

  @BeforeEach
  void createStore() {
    store = newStore();
  }

  @Test
  @DisplayName("정상 반환한 사용자 쓰기는 다음 트랜잭션에서 보인다")
  void successfulTransactionCommitsUser() {
    store.transact(
        session -> {
          session.insertUser(ALICE);
          return Optional.empty();
        });

    var user = store.transact(session -> session.user("alice"));

    // then
    assertThat(user).contains(new UserAccount("alice", "alice@example.com", true, Role.ADMIN, AT));
  }

  @Test
  @DisplayName("같은 이메일로 다른 사용자를 만들면 거부한다")
  void userEmailIsUnique() {
    givenAlice();

    // when·then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.insertUser(
                          new UserAccount("other", "alice@example.com", true, Role.MEMBER, AT));
                      return Optional.empty();
                    }))
        .isInstanceOf(RuntimeException.class);
    var user = store.transact(session -> session.userByEmail("alice@example.com"));
    assertThat(user).contains(new UserAccount("alice", "alice@example.com", true, Role.ADMIN, AT));
  }

  @Test
  @DisplayName("같은 사용자 id로 다시 만들면 거부하고 기존 사용자를 유지한다")
  void userIdIsUnique() {
    givenAlice();

    // when·then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.insertUser(
                          new UserAccount("alice", "other@example.com", false, Role.MEMBER, AT));
                      return Optional.empty();
                    }))
        .isInstanceOf(RuntimeException.class);
    var user = store.transact(session -> session.user("alice"));
    assertThat(user).contains(new UserAccount("alice", "alice@example.com", true, Role.ADMIN, AT));
  }

  @Test
  @DisplayName("한 원천 신원을 다른 사용자에게 중복 연결할 수 없다")
  void sourceIdentityIsUniqueAcrossUsers() {
    givenLinkedAlice();

    // when·then
    assertThatThrownBy(
            () ->
                store.transact(
                    session ->
                        session.putLink(
                            new IdentityLink(
                                SLACK, "U01", "bob", LinkMethod.ADMIN, AT, "alice", Set.of()))))
        .isInstanceOf(RuntimeException.class);
    var link = store.transact(session -> session.link(SLACK, "U01"));
    assertThat(link)
        .contains(new IdentityLink(SLACK, "U01", "alice", LinkMethod.ADMIN, AT, "alice", Set.of()));
  }

  @Test
  @DisplayName("같은 신원 id라도 다른 원천에서는 별개로 연결한다")
  void sourceIdentityIsScopedBySource() {
    givenLinkedAlice();
    var other = new SourceInstanceId("slack-b");
    registerSource(new SourceAccount(other, "slack"));

    store.transact(
        session ->
            session.putLink(
                new IdentityLink(other, "U01", "bob", LinkMethod.ADMIN, AT, "alice", Set.of())));

    // then
    var link = store.transact(session -> session.link(other, "U01"));
    assertThat(link)
        .contains(new IdentityLink(other, "U01", "bob", LinkMethod.ADMIN, AT, "alice", Set.of()));
  }

  @Test
  @DisplayName("등록 원천의 잠금은 true다")
  void lockSourceReturnsTrueForRegisteredSource() {
    // given
    registerSource(new SourceAccount(SLACK, "slack"));
    // when
    var locked =
        store.transact(
            session -> {
              session.lockAdministration();
              return session.lockSource(SLACK);
            });
    // then
    assertThat(locked).isTrue();
  }

  @Test
  @DisplayName("미등록 원천을 조회하면 empty다")
  void unknownSourceIsAbsent() {
    var source = store.transact(session -> session.source(MISSING));

    // then
    assertThat(source).isEmpty();
  }

  @Test
  @DisplayName("토큰 id와 저장 해시는 같은 토큰을 가리킨다")
  void tokenIdAndHashLocateSameToken() {
    givenAlice();
    var token =
        store.transact(
            session -> {
              session.tokens().store(HASH, "alice", "work", AT);
              return session.tokenByHash(HASH).orElseThrow();
            });

    var byId = store.transact(session -> session.token(token.tokenId()));

    // then
    assertThat(List.of(token, byId.orElseThrow()))
        .extracting(StoredToken::userId, stored -> stored.hash().value())
        .containsExactly(
            tuple("alice", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            tuple("alice", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
  }

  @Test
  @DisplayName("서로 다른 토큰 중 두 번째 id를 조회하면 해당 토큰을 반환한다")
  void tokenIdSelectsRequestedToken() {
    givenAlice();
    var secondHash =
        new TokenHash("cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34");
    var second =
        store.transact(
            session -> {
              session.insertUser(BOB);
              session.tokens().store(HASH, "alice", "work", AT);
              session.tokens().store(secondHash, "bob", "home", AT);
              return session.tokenByHash(secondHash).orElseThrow();
            });

    var token = store.transact(session -> session.token(second.tokenId()));

    // then
    assertThat(token.orElseThrow())
        .extracting(StoredToken::userId, stored -> stored.hash().value())
        .containsExactly("bob", "cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34");
  }

  @Test
  @DisplayName("토큰이 있어도 저장한 적 없는 id를 조회하면 empty다")
  void unknownTokenIdIsAbsent() {
    givenAlice();
    store.transact(
        session -> {
          session.tokens().store(HASH, "alice", "work", AT);
          return Optional.empty();
        });

    var token =
        store.transact(
            session -> session.token(UUID.fromString("00000000-0000-0000-0000-000000000001")));

    // then
    assertThat(token).isEmpty();
  }

  @Test
  @DisplayName("토큰의 저장 해시로는 다른 토큰을 찾을 수 없다")
  void unknownTokenHashIsAbsent() {
    givenAlice();
    store.transact(
        session -> {
          session.tokens().store(HASH, "alice", "work", AT);
          return Optional.empty();
        });

    var token = store.transact(session -> session.tokenByHash(TokenHash.of("synthetic-unknown")));

    // then
    assertThat(token).isEmpty();
  }

  @Test
  @DisplayName("콜백에서 예외가 나면 사용자 쓰기를 롤백한다")
  void exceptionRollsBackUserInsert() {
    // when·then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.insertUser(ALICE);
                      throw new IllegalStateException("synthetic transaction failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic transaction failure");
    var user = store.transact(session -> session.user("alice"));
    assertThat(user).isEmpty();
  }

  @Test
  @DisplayName("등록 원천의 종류가 바뀌면 관리 조회도 즉시 바뀐다")
  void sourceKindComesFromRegistration() {
    registerSource(new SourceAccount(SLACK, "slack"));

    registerSource(new SourceAccount(SLACK, "notion"));

    // then
    var source = store.transact(session -> session.source(SLACK));
    assertThat(source).contains(new SourceAccount(new SourceInstanceId("slack-a"), "notion"));
    var sources = store.transact(AdminStore.Session::sources);
    assertThat(sources)
        .containsExactly(new SourceAccount(new SourceInstanceId("slack-a"), "notion"));
  }

  @Test
  @DisplayName("미등록 원천의 잠금은 false다")
  void lockSourceReturnsFalseForUnknownSource() {
    var locked =
        store.transact(
            session -> {
              session.lockAdministration();
              return session.lockSource(MISSING);
            });

    // then
    assertThat(locked).isFalse();
  }

  @Test
  @DisplayName("사용자 변경과 감사는 함께 커밋된다")
  void writesCommitWithAudit() {
    store.transact(
        session -> {
          session.insertUser(ALICE);
          session.appendAudit(
              new NewAuditEvent(AT, "alice", "user.create", "user", "alice", Map.of()));
          return true;
        });

    // then
    assertThat(store.<Optional<UserAccount>>transact(session -> session.user("alice")))
        .contains(ALICE);
    assertThat(store.transact(session -> session.audit(new AuditQuery())).items())
        .extracting(event -> event.action())
        .containsExactly("user.create");
  }

  @Test
  @DisplayName("감사 저장 장애는 사용자 쓰기도 롤백한다")
  void auditFailureRollsBackUserWrite() {
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.insertUser(ALICE);
                      brokenAudit(session);
                      return true;
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(store.<Optional<UserAccount>>transact(session -> session.user("alice"))).isEmpty();
  }

  @Test
  @DisplayName("감사 저장 장애는 발급 토큰도 롤백한다")
  void auditFailureRollsBackTokenWrite() {
    givenAlice();
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.tokens().store(HASH, "alice", "work", AT);
                      brokenAudit(session);
                      return true;
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(store.<Optional<Principal>>transact(session -> session.tokens().authenticate(HASH)))
        .isEmpty();
  }

  @Test
  @DisplayName("감사 저장 장애는 조직 지정을 롤백한다")
  void auditFailureRollsBackScopeWrite() {
    registerSource(new SourceAccount(SLACK, "notion"));
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.designate(new ScopeDesignation(SLACK, "alice", AT));
                      brokenAudit(session);
                      return true;
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(store.<List<ScopeDesignation>>transact(AdminStore.Session::scopes)).isEmpty();
  }

  @Test
  @DisplayName("실패한 트랜잭션은 이전 신원 연결을 복구한다")
  void failedTransactionRestoresIdentityLinks() {
    givenLinkedAlice();
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.removeLink(SLACK, "U01");
                      brokenAudit(session);
                      return true;
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(store.<Optional<IdentityLink>>transact(session -> session.link(SLACK, "U01")))
        .contains(new IdentityLink(SLACK, "U01", "alice", LinkMethod.ADMIN, AT, "alice", Set.of()));
  }

  @Test
  @DisplayName("토큰 메타데이터에는 해시나 원문이 없다")
  void metadataNeverContainsTokenHash() {
    var raw = "synthetic-secret";
    var hash = TokenHash.of(raw);
    givenAlice();
    store.transact(
        session -> {
          session.tokens().store(hash, "alice", "work", AT);
          return true;
        });

    var page = store.transact(session -> session.tokenPage("alice", new PageRequest()));

    // then
    assertThat(page.items()).hasSize(1);
    assertThat(List.of(page.toString(), serializeMetadata(page.items())))
        .allSatisfy(
            value ->
                assertThat(value).doesNotContain(hash.value(), raw, "\"hash\"", "\"tokenHash\""));
  }

  @Test
  @DisplayName("트랜잭션 밖 세션은 읽기도 거부한다")
  void escapedSessionIsRejected() {
    var escaped = new AtomicReference<AdminStore.Session>();
    store.transact(
        session -> {
          escaped.set(session);
          return true;
        });

    // then
    assertThatThrownBy(() -> escaped.get().user("alice")).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @MethodSource("sessionOperations")
  @DisplayName("트랜잭션 밖에서는 모든 관리 세션 연산을 거부한다")
  void closedSessionRejectsEveryOperation(Consumer<AdminStore.Session> operation) {
    var escaped = store.transact(session -> session);
    // when·then
    assertThatThrownBy(() -> operation.accept(escaped))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  protected static Stream<Named<Consumer<AdminStore.Session>>> sessionOperations() {
    return Stream.concat(sessionReads(), sessionWrites());
  }

  private static Stream<Named<Consumer<AdminStore.Session>>> sessionReads() {
    return Stream.of(
        operation("admin count", AdminStore.Session::administratorCount),
        operation("active admin count", AdminStore.Session::activeAdministratorCount),
        operation("token holding admin count", AdminStore.Session::tokenHoldingAdministratorCount),
        operation("user", s -> s.user("alice")),
        operation("email", s -> s.userByEmail("alice@example.com")),
        operation("users", s -> s.users(new PageRequest())),
        operation("source", s -> s.source(SLACK)),
        operation("sources", AdminStore.Session::sources),
        operation("identities", s -> s.observedIdentities(SLACK)),
        operation("link", s -> s.link(SLACK, "U01")),
        operation("links", s -> s.links(Optional.empty(), new PageRequest())),
        operation("scopes", AdminStore.Session::scopes),
        operation("token port", AdminStore.Session::tokens),
        operation(
            "token id", s -> s.token(UUID.fromString("00000000-0000-0000-0000-000000000001"))),
        operation("token hash", s -> s.tokenByHash(HASH)),
        operation("token page", s -> s.tokenPage("alice", new PageRequest())),
        operation("audit", s -> s.audit(new AuditQuery())));
  }

  private static Stream<Named<Consumer<AdminStore.Session>>> sessionWrites() {
    return Stream.of(
        operation("admin lock", AdminStore.Session::lockAdministration),
        operation("source lock", s -> s.lockSource(SLACK)),
        operation("insert user", s -> s.insertUser(ALICE)),
        operation("activate", s -> s.setActive("alice", false)),
        operation("role", s -> s.setRole("alice", Role.MEMBER)),
        operation(
            "put link",
            s ->
                s.putLink(
                    new IdentityLink(
                        SLACK, "U01", "alice", LinkMethod.ADMIN, AT, "alice", Set.of()))),
        operation("remove link", s -> s.removeLink(SLACK, "U01")),
        operation("scope", s -> s.designate(new ScopeDesignation(SLACK, "alice", AT))),
        operation("revoke scope", s -> s.revokeScope(SLACK)),
        operation(
            "append audit",
            s ->
                s.appendAudit(
                    new NewAuditEvent(AT, "alice", "user.create", "user", "alice", Map.of()))));
  }

  private static Named<Consumer<AdminStore.Session>> operation(
      String name, Consumer<AdminStore.Session> action) {
    return Named.of(name, action);
  }

  @ParameterizedTest
  @MethodSource("tokenOperations")
  @DisplayName("트랜잭션 밖에서는 모든 세션 토큰 연산을 거부한다")
  void closedTokenPortRejectsEveryOperation(Consumer<UserTokens> operation) {
    var escaped = store.transact(AdminStore.Session::tokens);
    // when·then
    assertThatThrownBy(() -> operation.accept(escaped))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  protected static Stream<Named<Consumer<UserTokens>>> tokenOperations() {
    return Stream.of(
        Named.of("authenticate", tokens -> tokens.authenticate(HASH)),
        Named.of("store", tokens -> tokens.store(HASH, "alice", "work", AT)),
        Named.of("revoke", tokens -> tokens.revoke(HASH, AT)),
        Named.of("revoke all", tokens -> tokens.revokeAllOf("alice", AT)),
        Named.of("mark used", tokens -> tokens.markUsed(Map.of(HASH, AT))));
  }

  @Test
  @DisplayName("트랜잭션 밖 토큰 포트는 쓰기를 거부한다")
  void escapedTokensAreRejected() {
    givenAlice();
    var escaped = new AtomicReference<UserTokens>();
    store.transact(
        session -> {
          escaped.set(session.tokens());
          return true;
        });

    // then
    assertThatThrownBy(() -> escaped.get().store(HASH, "alice", "work", AT))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("관리 사용자 상태는 기존 인증 포트에 즉시 반영된다")
  void deactivationImmediatelyHidesToken() {
    givenAlice();
    store.transact(
        session -> {
          session.tokens().store(HASH, "alice", "work", AT);
          return session.setActive("alice", false);
        });

    // then
    assertThat(store.<Optional<Principal>>transact(session -> session.tokens().authenticate(HASH)))
        .isEmpty();
  }

  @Test
  @DisplayName("사용자는 id 순서 커서로 중복 없이 이어진다")
  void usersContinueAfterCursor() {
    givenAlice();
    store.transact(
        session -> {
          session.insertUser(BOB);
          return true;
        });
    var first = store.transact(session -> session.users(new PageRequest(1, Optional.empty())));

    var second = store.transact(session -> session.users(new PageRequest(1, first.nextCursor())));

    // then
    assertThat(second.items()).containsExactly(BOB);
    assertThat(second.nextCursor()).isEmpty();
  }

  @Test
  @DisplayName("잘못된 사용자 커서는 거부한다")
  void malformedCursorIsRejected() {
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> session.users(new PageRequest(1, Optional.of("invalid")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("관리 잠금이 겹친 트랜잭션의 커밋 순서를 보장한다")
  void administrationLockSerializesTransactions() throws Exception {
    // given
    try (var race = new StorageRace()) {
      var first = race.holdAdministration();
      // when
      var second = race.followAdministration();
      // then
      race.requireWaiting(second);
      race.release();
      assertThat(first.get()).isTrue();
      assertThat(second.get()).contains(ALICE);
    }
  }

  @Test
  @DisplayName("관리 쓰기가 기존 인증 포트에 즉시 보인다")
  void committedTokenIsVisibleToExistingAuthentication() {
    givenAlice();
    store.transact(
        session -> {
          session.tokens().store(HASH, "alice", "work", AT);
          return true;
        });

    var principal = authentication().authenticate(HASH);

    // then
    assertThat(principal).contains(new Principal("alice"));
  }

  @Test
  @DisplayName("다른 스레드에서 세션을 사용하면 거부한다")
  void foreignThreadSessionIsRejected() {
    try (var race = new StorageRace()) {
      // then
      assertThatThrownBy(() -> store.transact(session -> race.callForeign(session)))
          .hasRootCauseInstanceOf(IllegalStateException.class);
    }
  }

  private final class StorageRace implements AutoCloseable {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration BLOCKED = Duration.ofMillis(200);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch following = new CountDownLatch(1);

    Future<Boolean> holdAdministration() {
      var result =
          workers.submit(
              () ->
                  store.transact(
                      session -> {
                        session.lockAdministration();
                        session.insertUser(ALICE);
                        held.countDown();
                        await(release);
                        return true;
                      }));
      await(held);
      return result;
    }

    Future<Optional<UserAccount>> followAdministration() {
      return workers.submit(
          () -> {
            following.countDown();
            return store.transact(
                session -> {
                  session.lockAdministration();
                  return session.user("alice");
                });
          });
    }

    void requireWaiting(Future<?> future) {
      await(following);
      assertThatThrownBy(() -> future.get(BLOCKED.toMillis(), TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
    }

    boolean callForeign(AdminStore.Session session) {
      try {
        return workers
            .submit(() -> session.user("alice").isPresent())
            .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    void release() {
      release.countDown();
    }

    private void await(CountDownLatch gate) {
      try {
        if (!gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
          throw new IllegalStateException("Storage race gate timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }

    @Override
    public void close() {
      release();
      workers.close();
    }
  }

  @Test
  @DisplayName("같은 시각의 감사도 id 내림차순 커서로 이어진다")
  void auditCursorKeepsTiesInOrder() {
    givenAudit("user.create", "alice");
    givenAudit("user.deactivate", "bob");
    var first =
        store.transact(
            session ->
                session.audit(
                    new AuditQuery(
                        1,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

    var second =
        store.transact(
            session ->
                session.audit(
                    new AuditQuery(
                        1,
                        first.nextCursor(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

    // then
    assertThat(first.items())
        .extracting(event -> event.action())
        .containsExactly("user.deactivate");
    assertThat(second.items()).extracting(event -> event.action()).containsExactly("user.create");
    assertThat(second.nextCursor()).isEmpty();
  }

  @Test
  @DisplayName("감사 선택 필터는 모두 함께 적용된다")
  void auditFiltersAreConjunctive() {
    givenAudit("user.create", "alice");
    givenAudit("user.create", "bob");
    givenAudit("user.deactivate", "alice");

    var page =
        store.transact(
            session ->
                session.audit(
                    new AuditQuery(
                        50,
                        Optional.empty(),
                        Optional.of("user.create"),
                        Optional.of("alice"),
                        Optional.of("user"),
                        Optional.of("alice"))));

    // then
    assertThat(page.items()).extracting(event -> event.targetId()).containsExactly("alice");
  }

  @Test
  @DisplayName("비어 있는 감사에서도 잘못된 커서를 거부한다")
  void malformedAuditCursorIsRejectedForEmptyStorage() {
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session ->
                        session.audit(
                            new AuditQuery(
                                50,
                                Optional.of("audit.YmFk"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("잘못된 복합 신원 커서를 거부한다")
  void malformedLinkCursorIsRejected() {
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session ->
                        session.links(
                            Optional.empty(), new PageRequest(50, Optional.of("links.YmFk")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("잘못된 토큰 식별자 커서를 거부한다")
  void malformedTokenCursorIsRejected() {
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session ->
                        session.tokenPage(
                            "alice", new PageRequest(50, Optional.of("tokens.YmFk")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("토큰 폐기와 사용 기록 갱신은 식별자를 보존한다")
  void tokenUpdatesPreserveMetadataId() {
    givenAlice();
    var before =
        store.transact(
            session -> {
              session.tokens().store(HASH, "alice", "work", AT);
              return session.tokenByHash(HASH).orElseThrow().tokenId();
            });

    store.transact(
        session -> {
          session.tokens().markUsed(Map.of(HASH, AT));
          session.tokens().revoke(HASH, AT);
          return true;
        });

    var metadata = store.transact(session -> session.tokenPage("alice", new PageRequest()));
    // then
    assertThat(metadata.items())
        .containsExactly(
            new TokenMetadata(before, "alice", "work", AT, Optional.of(AT), Optional.of(AT)));
  }

  @Test
  @DisplayName("실패한 트랜잭션은 이미 추가한 감사도 롤백한다")
  void failureRollsBackAudit() {
    // then
    assertThatThrownBy(
            () ->
                store.transact(
                    session -> {
                      session.appendAudit(
                          new NewAuditEvent(
                              AT,
                              "alice",
                              "user.create",
                              "user",
                              "alice",
                              Map.of("role", "admin")));
                      throw new IllegalStateException("synthetic transaction failure");
                    }))
        .isInstanceOf(IllegalStateException.class);

    var page = store.transact(session -> session.audit(new AuditQuery()));
    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("공용 시드의 자동 연결은 관리 조회에서도 AUTO다")
  void seedPreservesAutoLinkMethod() {
    // given
    seedAccessDirectory(
        AccessDirectory.empty()
            .withUser(new AccessDirectory.User("alice", "alice@example.com", true))
            .withLink(new AccessDirectory.Link(SLACK, "U01", "alice", "auto")));
    // when
    var link = store.transact(session -> session.link(SLACK, "U01"));
    // then
    assertThat(link.orElseThrow().method()).isEqualTo(LinkMethod.AUTO);
  }

  @Test
  @DisplayName("날짜가 잘못된 감사 커서는 입력 오류로 거부한다")
  void malformedAuditTimestampIsRejected() {
    // when·then
    assertThatThrownBy(
            () ->
                store.transact(
                    session ->
                        session.audit(
                            new AuditQuery(
                                50,
                                Optional.of("audit.aW52YWxpZHwx"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("지정한 조직 범위의 주체와 시각을 그대로 조회한다")
  void designationPreservesMetadata() {
    // given
    var scope = new ScopeDesignation(SLACK, "alice", AT);
    // when
    store.transact(session -> session.designate(scope));
    // then
    var scopes = store.transact(AdminStore.Session::scopes);
    assertThat(scopes)
        .containsExactly(new ScopeDesignation(new SourceInstanceId("slack-a"), "alice", AT));
  }

  @Test
  @DisplayName("비활성 관리자도 전체 관리자 수에 포함된다")
  void administratorCountIncludesInactiveUsers() {
    // given
    givenAlice();
    store.transact(session -> session.setActive("alice", false));
    // when
    int count = store.transact(AdminStore.Session::administratorCount);
    // then
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("일반 구성원은 전체 관리자 수에 포함하지 않는다")
  void administratorCountExcludesMembers() {
    givenAlice();
    store.transact(
        s -> {
          s.insertUser(BOB);
          return true;
        });
    // when
    int count = store.transact(AdminStore.Session::administratorCount);
    // then
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("원천 목록은 등록 순서와 무관하게 id 오름차순이다")
  void sourcesAreOrderedById() {
    registerSource(new SourceAccount(new SourceInstanceId("slack-b"), "slack"));
    registerSource(new SourceAccount(SLACK, "slack"));
    // when
    var sources = store.transact(AdminStore.Session::sources);
    // then
    assertThat(sources)
        .extracting(source -> source.id().value())
        .containsExactly("slack-a", "slack-b");
  }

  @Test
  @DisplayName("새 조직 지정은 변경됨을 반환한다")
  void newDesignationReportsChange() {
    givenAlice();
    registerSource(new SourceAccount(SLACK, "slack"));
    // when
    boolean changed = store.transact(s -> s.designate(new ScopeDesignation(SLACK, "alice", AT)));
    // then
    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("같은 조직 지정 재시도는 변경 없음이다")
  void repeatedDesignationReportsNoChange() {
    givenAlice();
    registerSource(new SourceAccount(SLACK, "slack"));
    store.transact(s -> s.designate(new ScopeDesignation(SLACK, "alice", AT)));
    // when
    boolean changed = store.transact(s -> s.designate(new ScopeDesignation(SLACK, "alice", AT)));
    // then
    assertThat(changed).isFalse();
  }

  @Test
  @DisplayName("다른 원천을 지정해도 앞선 조직 지정은 유지된다")
  void designationDoesNotReplaceAnotherSource() {
    givenAlice();
    var other = new SourceInstanceId("slack-b");
    registerSource(new SourceAccount(SLACK, "slack"));
    registerSource(new SourceAccount(other, "slack"));
    store.transact(s -> s.designate(new ScopeDesignation(SLACK, "alice", AT)));
    // when
    boolean changed = store.transact(s -> s.designate(new ScopeDesignation(other, "alice", AT)));
    // then
    assertThat(changed).isTrue();
    assertThat(store.transact(AdminStore.Session::scopes))
        .extracting(s -> s.source().value())
        .containsExactly("slack-a", "slack-b");
  }

  @Test
  @DisplayName("조직 지정 목록은 생성 순서와 무관하게 원천 id 오름차순이다")
  void scopesAreOrderedBySource() {
    givenAlice();
    var other = new SourceInstanceId("slack-b");
    registerSource(new SourceAccount(SLACK, "slack"));
    registerSource(new SourceAccount(other, "slack"));
    store.transact(s -> s.designate(new ScopeDesignation(other, "alice", AT)));
    store.transact(s -> s.designate(new ScopeDesignation(SLACK, "alice", AT)));
    // when
    var scopes = store.transact(AdminStore.Session::scopes);
    // then
    assertThat(scopes).extracting(s -> s.source().value()).containsExactly("slack-a", "slack-b");
  }

  @Test
  @DisplayName("없는 사용자의 역할 변경은 변경 없음이다")
  void missingUserRoleReportsNoChange() {
    // when
    boolean changed = store.transact(s -> s.setRole("missing", Role.ADMIN));
    // then
    assertThat(changed).isFalse();
  }

  @Test
  @DisplayName("새 신원 연결은 변경됨을 반환한다")
  void newLinkReportsChange() {
    givenAlice();
    registerSource(new SourceAccount(SLACK, "slack"));
    // when
    boolean changed =
        store.transact(
            s ->
                s.putLink(
                    new IdentityLink(
                        SLACK, "U01", "alice", LinkMethod.ADMIN, AT, "alice", Set.of())));
    // then
    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("같은 연결 재시도는 변경 없음이다")
  void repeatedLinkReportsNoChange() {
    givenLinkedAlice();
    // when
    boolean changed =
        store.transact(
            s ->
                s.putLink(
                    new IdentityLink(
                        SLACK, "U01", "alice", LinkMethod.ADMIN, AT, "alice", Set.of())));
    // then
    assertThat(changed).isFalse();
  }

  @Test
  @DisplayName("신원을 해제하면 다른 신원 연결은 보존한다")
  void removingLinkPreservesAnotherIdentity() {
    givenLinkedAlice();
    store.transact(
        s ->
            s.putLink(
                new IdentityLink(SLACK, "U02", "alice", LinkMethod.ADMIN, AT, "alice", Set.of())));
    // when
    boolean changed = store.transact(s -> s.removeLink(SLACK, "U01"));
    // then
    assertThat(changed).isTrue();
    assertThat(store.transact(s -> s.links(Optional.empty(), new PageRequest())).items())
        .extracting(IdentityLink::sourceUserId)
        .containsExactly("U02");
  }

  @Test
  @DisplayName("없는 신원의 해제는 기존 연결을 보존하고 변경 없음이다")
  void missingLinkRemovalReportsNoChange() {
    givenLinkedAlice();
    // when
    boolean changed = store.transact(s -> s.removeLink(SLACK, "missing"));
    // then
    assertThat(changed).isFalse();
    assertThat(store.transact(s -> s.links(Optional.empty(), new PageRequest())).items())
        .extracting(IdentityLink::sourceUserId)
        .containsExactly("U01");
  }

  @Test
  @DisplayName("사용자의 토큰 목록에 다른 사용자의 토큰을 섞지 않는다")
  void tokenPageExcludesOtherUsers() {
    givenAlice();
    store.transact(
        s -> {
          s.insertUser(BOB);
          s.tokens().store(HASH, "alice", "work", AT);
          s.tokens().store(TokenHash.of("synthetic-bob-token"), "bob", "home", AT);
          return true;
        });
    // when
    var page = store.transact(s -> s.tokenPage("alice", new PageRequest()));
    // then
    assertThat(page.items()).extracting(TokenMetadata::userId).containsExactly("alice");
  }

  @Test
  @DisplayName("감사 번호와 시각 순서가 달라도 마지막 커서를 이어간다")
  void auditCursorHandlesFirstIdAtNewerTimestamp() {
    givenAudit("user.create", "alice");
    store.transact(
        session -> {
          session.appendAudit(
              new NewAuditEvent(
                  AT.minusSeconds(1), "alice", "user.activate", "user", "alice", Map.of()));
          return true;
        });
    var first =
        store.transact(
            session ->
                session.audit(
                    new AuditQuery(
                        1,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));
    // when
    var second =
        store.transact(
            session ->
                session.audit(
                    new AuditQuery(
                        1,
                        first.nextCursor(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));
    // then
    assertThat(first.items()).extracting(event -> event.action()).containsExactly("user.create");
    assertThat(second.items()).extracting(event -> event.action()).containsExactly("user.activate");
    assertThat(second.nextCursor()).isEmpty();
  }

  @Test
  @DisplayName("토큰 없는 활성 관리자도 역할을 구분해 정확히 센다")
  void activeAdministratorCountIncludesTokenlessAdministrators() {
    // given
    givenAlice();
    store.transact(
        session -> {
          session.insertUser(BOB);
          session.insertUser(
              new UserAccount(
                  "7e7a7a52-532b-46f1-9e75-18298151c1db",
                  "charlie@example.com",
                  true,
                  Role.ADMIN,
                  AT));
          return true;
        });
    // when
    int count = store.transact(AdminStore.Session::activeAdministratorCount);
    // then
    assertThat(count).isEqualTo(2);
  }

  @Test
  @DisplayName("비활성 관리자는 활성 관리자 수에서 빠진다")
  void activeAdministratorCountExcludesInactiveUsers() {
    // given
    givenAlice();
    store.transact(session -> session.setActive("alice", false));
    // when
    int count = store.transact(AdminStore.Session::activeAdministratorCount);
    // then
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("커밋한 조직 지정은 기존 신원 조회에 즉시 보인다")
  void committedScopeIsVisibleToExistingIdentityReader() {
    // given
    givenAlice();
    store.transact(session -> session.designate(new ScopeDesignation(SLACK, "alice", AT)));
    // when
    var keys = identityReader().accessKeysOf("alice", new DomainPolicy(Set.of(), Map.of()));
    // then
    assertThat(keys)
        .contains(
            Set.of(new AccessKey(AccessKey.Kind.SCOPE, new SourceInstanceId("slack-a"), "*")));
  }

  @Test
  @DisplayName("커밋한 연결은 기존 신원 조회에 즉시 보인다")
  void committedLinkIsVisibleToExistingIdentityReader() {
    // given
    seedAccessDirectory(
        AccessDirectory.empty()
            .withUser(new AccessDirectory.User("alice", "alice@example.com", true))
            .withIdentity(new AccessDirectory.Identity(SLACK, "U01", false, false)));
    store.transact(
        session ->
            session.putLink(
                new IdentityLink(SLACK, "U01", "alice", LinkMethod.AUTO, AT, "system", Set.of())));
    // when
    var keys = identityReader().accessKeysOf("alice", new DomainPolicy(Set.of(), Map.of()));
    // then
    assertThat(keys)
        .contains(
            Set.of(new AccessKey(AccessKey.Kind.USER, new SourceInstanceId("slack-a"), "U01")));
  }

  private void givenAudit(String action, String target) {
    store.transact(
        session -> {
          session.appendAudit(
              new NewAuditEvent(AT, "alice", action, "user", target, Map.of("role", "member")));
          return true;
        });
  }

  @Nested
  @DisplayName("관리 세션으로 토큰을 저장할 때")
  class SessionTokens extends UserTokensContract {
    @Override
    protected UserTokens newTokens() {
      return new TransactedTokens();
    }

    @Override
    protected void seed(AccessDirectory directory) {
      seedAccessDirectory(directory);
    }

    @Override
    protected void setActive(String user, boolean active) {
      store.transact(session -> session.setActive(user, active));
    }

    @Override
    protected Optional<Instant> lastUsedAt(TokenHash hash) {
      return metadata(hash).lastUsedAt();
    }

    @Override
    protected Optional<Instant> revokedAt(TokenHash hash) {
      return metadata(hash).revokedAt();
    }

    private TokenMetadata metadata(TokenHash hash) {
      return store.transact(
          session -> {
            var token = session.tokenByHash(hash).orElseThrow();
            return session.tokenPage(token.userId(), new PageRequest()).items().stream()
                .filter(item -> item.tokenId().equals(token.tokenId()))
                .findFirst()
                .orElseThrow();
          });
    }
  }

  private final class TransactedTokens implements UserTokens {
    @Override
    public Optional<Principal> authenticate(TokenHash hash) {
      return store.transact(session -> session.tokens().authenticate(hash));
    }

    @Override
    public void store(TokenHash hash, String user, String label, Instant at) {
      store.transact(
          session -> {
            session.tokens().store(hash, user, label, at);
            return true;
          });
    }

    @Override
    public void revoke(TokenHash hash, Instant at) {
      store.transact(
          session -> {
            session.tokens().revoke(hash, at);
            return true;
          });
    }

    @Override
    public void revokeAllOf(String user, Instant at) {
      store.transact(
          session -> {
            session.tokens().revokeAllOf(user, at);
            return true;
          });
    }

    @Override
    public void markUsed(Map<TokenHash, Instant> used) {
      store.transact(
          session -> {
            session.tokens().markUsed(used);
            return true;
          });
    }
  }

  @SuppressWarnings("NullAway")
  private void brokenAudit(AdminStore.Session session) {
    session.appendAudit(new NewAuditEvent(AT, null, "user.create", "user", "alice", Map.of()));
  }

  private void givenAlice() {
    store.transact(
        session -> {
          session.insertUser(ALICE);
          return Optional.empty();
        });
  }

  private void givenLinkedAlice() {
    registerSource(new SourceAccount(SLACK, "slack"));
    store.transact(
        session -> {
          session.insertUser(ALICE);
          session.insertUser(BOB);
          session.putLink(
              new IdentityLink(SLACK, "U01", "alice", LinkMethod.ADMIN, AT, "alice", Set.of()));
          return Optional.empty();
        });
  }
}
