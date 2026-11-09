package io.scos.engine.admin;

import io.scos.engine.admin.AdminDiagnostics.DocumentAccessView;
import io.scos.engine.admin.AdminDiagnostics.SourceAccessView;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 사람의 사용자·토큰·신원 관리 경계. 현재 활성 admin을 확인하며, 활성 구성원은 본인 토큰 발급·폐기·메타데이터 조회만 할 수 있다. 이메일은 strip·소문자로
 * 정규화하며 비어 있거나 @의 양쪽이 없으면 거부한다. 같은 상태 변경은 무변경이며 감사도 추가하지 않는다. 변경과 감사는 함께 커밋한다. 실패는 Failure의 고정
 * code로 전달하고 저장소 장애는 런타임 예외로 전파한다. 취소는 호출 스레드 인터럽트와 저장소의 트랜잭션 경계를 따른다.
 */
public interface AdminPort {
  UserAccount createUser(Principal actor, String email, Role role);

  void deactivateUser(Principal actor, String userId);

  void activateUser(Principal actor, String userId);

  void changeRole(Principal actor, String userId, Role role);

  /** id 오름차순의 불투명 커서 페이지. 잘못된 커서는 invalid_request다. */
  Page<UserAccount> users(Principal actor, PageRequest page);

  /** 활성 대상에게만 발급하며 커밋 성공 뒤 원문을 반환한다. label은 strip 후 1~100자이며 제어 문자를 거부한다. */
  IssuedToken issueToken(Principal actor, String userId, String label);

  /** 없는 id는 not_found, 타인의 id는 forbidden이다. 재폐기는 최초 시각과 감사를 보존한다. */
  void revokeToken(Principal actor, UUID tokenId);

  /** 비밀 없는 메타데이터 페이지. 잘못된 커서는 invalid_request다. */
  Page<TokenMetadata> tokens(Principal actor, String userId, PageRequest page);

  /** 등록 원천과 존재하는 사용자에 수동 연결한다. 다른 사용자의 연결은 conflict다. */
  void linkIdentity(Principal actor, String userId, SourceInstanceId source, String sourceUserId);

  /** 현재 연결만 지운다. 적격 신원은 다음 자동 연결에서 복구될 수 있어 지속 회수는 사용자 비활성화로 한다. */
  void unlinkIdentity(Principal actor, SourceInstanceId source, String sourceUserId);

  /**
   * source/id 오름차순 목록. 현재 관측에 따라 unobserved·guest·deleted 경고를 계산한다. unobserved가 없으면 해당 원천에서 관측된
   * 신원이다. 잘못된 커서는 invalid_request다.
   */
  Page<IdentityLink> links(Principal actor, Optional<String> userId, PageRequest page);

  /** 등록된 Notion 인스턴스만 조직 전체에 공개한다. 같은 지정은 최초 주체·시각을 보존한다. */
  ScopeDesignation designateScope(Principal actor, SourceInstanceId source);

  /** 지정과 scope 키를 즉시 회수한다. 없는 지정의 해제는 변경과 감사가 없다. */
  void revokeScope(Principal actor, SourceInstanceId source);

  /** 현재 활성 admin만 조직 전체 지정 목록을 조회한다. */
  List<ScopeDesignation> scopes(Principal actor);

  /** (at,id) 내림차순 감사 페이지. 잘못된 커서는 invalid_request다. */
  Page<AuditEvent> audit(Principal actor, AuditQuery query);

  /** 원천별 본문·접근 결과와 추세·같은 run의 비용. 현재 활성 admin만 조회한다. */
  List<SourceAccessView> sourceAccess(Principal actor);

  /** 저장된 소유·차단 원인·실제 사용자 판정. 현재 활성 admin만 조회한다. */
  Optional<DocumentAccessView> explainAccess(
      Principal actor, String documentId, Optional<String> userId);

  /** 이메일·SQL·자격 증명을 포함하지 않는 관리 정책 실패. */
  final class Failure extends RuntimeException {
    private final String code;

    public Failure(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
