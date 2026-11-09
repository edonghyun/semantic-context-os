package io.scos.engine.admin;

import java.util.UUID;

/** 운영자 DB 권한으로 수행하는 최초 관리자 준비. HTTP 관리 포트로 노출하지 않는다. */
public interface AdminBootstrap {
  BootstrapResult bootstrap(String email, TokenDelivery delivery);

  /** 트랜잭션 안에서 원문을 보호 파일로 전달한다. 실패하면 예외를 전파한다. */
  @FunctionalInterface
  interface TokenDelivery {
    void save(IssuedToken token);
  }

  /** 원문이나 해시가 없는 완료 결과. */
  record BootstrapResult(String userId, UUID tokenId) {}

  /** 고정 실패 코드. 복구 파일이 필요하면 삭제하지 말고 운영자가 커밋 결과를 확인해야 한다. */
  final class Failure extends RuntimeException {
    private final boolean recoveryFileRequired;

    public Failure(String code, boolean recoveryFileRequired) {
      super(code);
      this.recoveryFileRequired = recoveryFileRequired;
    }

    /** 진단 원인은 보존하지만 표면에는 고정 코드만 출력한다. */
    public Failure(String code, boolean recoveryFileRequired, Throwable cause) {
      super(code, cause);
      this.recoveryFileRequired = recoveryFileRequired;
    }

    public boolean recoveryFileRequired() {
      return recoveryFileRequired;
    }
  }
}
