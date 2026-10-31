package io.scos.collection.internal.credentials;

import io.scos.collection.internal.CredentialRef;
import io.scos.spi.connector.CredentialResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * credentialRef의 스킴 접두사(`<scheme>:`)로 해석기를 고른다(ADR-0022 §2). 접두사가 없거나 스킴 이름 모양(`[a-z][a-z0-9-]*`)이
 * 아닌 ref는 받지 않는다. 그 경우 ref 자체는 오류에 싣지 않는다 — 토큰이나 Basic "email:token"을 ref 자리에 잘못 넣었을 수 있다. 푼 값은 여러
 * 줄이어도 그대로 돌려준다 — 헤더로 보낼 수 없는 문자(줄바꿈·NUL 등)는 헤더 지점({@link
 * io.scos.spi.connector.CredentialResolver#headerValue})이 거부한다.
 */
public final class SchemeCredentialResolver implements CredentialResolver {
  private final Map<String, CredentialScheme> byName;

  private SchemeCredentialResolver(Map<String, CredentialScheme> byName) {
    this.byName = byName;
  }

  public static SchemeCredentialResolver of(List<CredentialScheme> schemes) {
    Map<String, CredentialScheme> byName = new LinkedHashMap<>();
    for (CredentialScheme scheme : schemes) {
      if (byName.putIfAbsent(scheme.name(), scheme) != null)
        throw new IllegalArgumentException("credential scheme registered twice: " + scheme.name());
    }
    return new SchemeCredentialResolver(byName);
  }

  /** 제품 조립: `env`·`file`·`command`(시간 상한 {@link CommandCredentials#DEFAULT_TIMEOUT}). */
  public static SchemeCredentialResolver standard(Map<String, String> environment) {
    return of(
        List.of(
            new EnvironmentCredentials(environment),
            new FileCredentials(),
            new CommandCredentials(CommandCredentials.DEFAULT_TIMEOUT)));
  }

  @Override
  public String resolve(String credentialRef) {
    // 스킴 이름 모양이 아니면(Basic "email:token"을 붙여 넣은 경우 등) 그 부분을 싣지 않는다
    CredentialRef ref =
        CredentialRef.parse(credentialRef)
            .orElseThrow(
                () ->
                    CredentialFailures.invalid(
                        "credentialRef has no scheme prefix; use " + supportedPrefixes()));
    CredentialScheme scheme = byName.get(ref.scheme());
    if (scheme == null)
      throw CredentialFailures.invalid(
          "unsupported credential scheme: "
              + ref.scheme()
              + ". supported schemes: "
              + String.join(", ", byName.keySet()));
    // 여러 줄 값(서비스 계정 JSON 등)은 그대로 돌려준다 — 헤더에 실을 값은 헤더 지점(CredentialResolver.headerValue)이 거른다
    return scheme.resolve(ref.reference());
  }

  private String supportedPrefixes() {
    return String.join(", ", byName.keySet().stream().map(n -> n + ":<…>").toList());
  }
}
