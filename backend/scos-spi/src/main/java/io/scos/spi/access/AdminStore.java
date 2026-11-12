package io.scos.spi.access;

import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.StoredToken;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** 관리 변경과 토큰·감사를 함께 커밋하는 저장 경계. 저장소 장애는 런타임 예외로 전파한다. */
public interface AdminStore {
  /** 정상 반환에만 커밋한다. 예외면 모든 변경을 롤백한다. Session은 콜백의 스레드·수명 안에서만 유효하다. */
  <T> T transact(Function<Session, T> work);

  /** 세션이나 tokens()를 콜백 밖에서 사용하면 IllegalStateException으로 거부한다. */
  interface Session {
    /** bootstrap·사람 쓰기·자동 연결을 직렬화한다. 현재 actor 검사보다 먼저 호출한다. */
    void lockAdministration();

    /** 비활성 사용자를 포함한 admin 수. bootstrap 재실행 방지에 쓴다. */
    int administratorCount();

    /** 폐기되지 않은 토큰을 하나 이상 가진 활성 관리자 수. 관리 잠금 아래 변경 후 가용성을 판정한다. */
    int tokenHoldingAdministratorCount();

    Optional<UserAccount> user(String userId);

    Optional<UserAccount> userByEmail(String email);

    /** id 오름차순 keyset. 잘못된 커서는 IllegalArgumentException이다. */
    Page<UserAccount> users(PageRequest page);

    /** id 또는 정규화된 email이 중복되면 런타임 예외로 거부한다. */
    void insertUser(UserAccount user);

    /** 실제 상태가 바뀐 경우만 true. 없는 사용자는 false다. */
    boolean setActive(String userId, boolean active);

    boolean setRole(String userId, Role role);

    /**
     * 수집 병합과 같은 소스 잠금을 tx 종료까지 유지한다. 미등록 소스면 false. lockAdministration 다음, 현재 관측·연결 재조회 전에 호출한다. 여러
     * 소스는 id 오름차순이다.
     */
    boolean lockSource(SourceInstanceId source);

    /** 잠금 없는 등록 메타데이터 조회. 미등록 소스는 empty다. */
    Optional<SourceAccount> source(SourceInstanceId source);

    /** 등록된 소스의 id·kind만 반환하며 설정·자격 증명은 노출하지 않는다. */
    List<SourceAccount> sources();

    List<ObservedIdentity> observedIdentities(SourceInstanceId source);

    Optional<IdentityLink> link(SourceInstanceId source, String sourceUserId);

    /** 같은 source/id의 다른 사용자 연결은 거부한다. 같은 사용자 갱신은 실제 변경에만 true다. */
    boolean putLink(IdentityLink link);

    boolean removeLink(SourceInstanceId source, String sourceUserId);

    /** source/id 오름차순 keyset. userId가 있으면 해당 사용자만 반환한다. */
    Page<IdentityLink> links(Optional<String> userId, PageRequest page);

    boolean designate(ScopeDesignation scope);

    boolean revokeScope(SourceInstanceId source);

    List<ScopeDesignation> scopes();

    /** 같은 트랜잭션에 참여한다. 저장 후 tokenByHash로 생성된 tokenId를 얻는다. */
    UserTokens tokens();

    /** 해시를 포함하므로 결과는 세션의 관리 처리 안에서만 사용한다. */
    Optional<StoredToken> token(UUID tokenId);

    Optional<StoredToken> tokenByHash(TokenHash hash);

    /** 응답 메타데이터에는 원문·해시가 없다. */
    Page<TokenMetadata> tokenPage(String userId, PageRequest page);

    /** id는 저장소가 만든다. 실패하면 같은 tx의 변경도 롤백한다. detail에는 비밀을 전달하지 않는다. */
    void appendAudit(NewAuditEvent event);

    /** (at,id) 내림차순 keyset. 잘못된 커서는 IllegalArgumentException이다. */
    Page<AuditEvent> audit(AuditQuery query);
  }
}
