package io.scos.spi.access;

import java.util.Optional;
import java.util.Set;

/** 사용자 접근 키를 한 번의 저장소 조회로 계산하는 읽기 전용 포트. */
public interface IdentityStore {
  /**
   * 활성 사용자의 키 전부(없으면 빈 집합), 미등록·비활성이면 empty다. guest·deleted·미관측 연결 신원은 제외하고 합성 그룹 참조는 한 단계만 펼친다. 조회
   * 실패는 전파하며 호출 스레드 인터럽트는 어댑터의 I/O 규약을 따른다. 결과는 캐시하지 않는다. id에 콜론·퍼센트가 든 인스턴스는 그 인스턴스의 키만 제외하고 경고를
   * 남긴다. 경고는 어댑터 클래스 이름의 {@link System.Logger}에 {@link System.Logger.Level#WARNING}으로 기록한다.
   */
  Optional<Set<AccessKey>> accessKeysOf(String userId, DomainPolicy domains);
}
