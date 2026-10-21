package io.scos.collection.internal;

import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 데몬 기동 검증(TS source-service-runner.ts validateStartup, ADR-0022 §5): 켜진 소스마다 어댑터가 등록돼 있고 설정이
 * 맞는지(validateConfig) 보고, 서로 다른 credentialRef를 모두 한 번씩 풀어 본다. 하나라도 실패하면 {@link
 * IllegalStateException}으로 기동을 거부한다 — 잘못 이전한 등록값(경로·변수·권한)이 health 녹색 뒤의 run 실패로만 드러나지 않게. 메시지는
 * "source <id> (<kind>): <사유>"이고, 사유는 검증 실패의 메시지(자격 증명 값을 싣지 않도록 만든 것)다.
 */
public final class SourceStartupCheck {
  private final SourceInstanceStore sources;
  private final SourceAdapters adapters;
  private final CredentialResolver credentials;

  public SourceStartupCheck(
      SourceInstanceStore sources, SourceAdapters adapters, CredentialResolver credentials) {
    this.sources = sources;
    this.adapters = adapters;
    this.credentials = credentials;
  }

  public void verify() {
    Map<String, SourceInstance> firstUserOfRef = new LinkedHashMap<>();
    for (SourceInstance source : sources.listEnabled()) {
      try {
        adapters.require(source.kind()).validateConfig(source.config());
      } catch (RuntimeException e) {
        throw rejected(source, reason(e));
      }
      firstUserOfRef.putIfAbsent(source.credentialRef(), source);
    }
    for (Map.Entry<String, SourceInstance> ref : firstUserOfRef.entrySet()) {
      String value;
      try {
        value = credentials.resolve(ref.getKey());
      } catch (RuntimeException e) {
        throw rejected(ref.getValue(), reason(e));
      }
      if (value == null || value.isEmpty())
        throw rejected(ref.getValue(), "Source credential is empty: " + ref.getKey());
    }
  }

  /** 검증 실패의 사유. 공급자 호출 예외는 분류된 메시지만(원인 예외는 싣지 않는다). */
  private static String reason(RuntimeException e) {
    return e instanceof CallFailureException p ? p.failure().message() : e.getMessage();
  }

  private static IllegalStateException rejected(SourceInstance source, String reason) {
    return new IllegalStateException(
        "source " + source.id().value() + " (" + source.kind() + "): " + reason);
  }
}
