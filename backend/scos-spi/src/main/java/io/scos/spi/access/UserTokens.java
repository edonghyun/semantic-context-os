package io.scos.spi.access;

import io.scos.spi.store.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 토큰 원문을 받지 않는 사용자 인증·폐기·사용 기록 포트(접근 설계 §6.1). 저장소 장애는 런타임 예외로 전파하며 어댑터의 동기 I/O·호출 스레드 인터럽트 규약을
 * 따른다.
 */
public interface UserTokens {
  /** 미등록·폐기·비활성은 모두 empty다. 조회 실패는 전파한다. */
  Optional<Principal> authenticate(TokenHash hash);

  /** 등록된 사용자(비활성 포함)의 해시를 한 번만 저장한다. 미등록 사용자·동일 해시 재등록은 런타임 예외로 거부하고 기존 행을 보존한다. */
  void store(TokenHash hash, String userId, String label, Instant at);

  /** 해당 해시를 폐기한다. 모르는 해시는 무시하고, 이미 폐기한 토큰은 최초 폐기 시각을 보존한다. */
  void revoke(TokenHash hash, Instant at);

  /** 사용자의 모든 토큰을 폐기한다. 이미 폐기한 토큰의 최초 시각을 보존하고 사용자 재활성화로 되살아나지 않는다. */
  void revokeAllOf(String userId, Instant at);

  /** 쓰기 연결로 더 늦은 시각만 반영한다. 빈 입력·모르는 해시는 무시한다. */
  void markUsed(Map<TokenHash, Instant> lastUsed);
}
