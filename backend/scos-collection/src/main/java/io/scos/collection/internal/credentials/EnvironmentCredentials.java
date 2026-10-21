package io.scos.collection.internal.credentials;

import java.util.Map;

/** `env:<변수 이름>` — 프로세스 환경변수(TS EnvironmentCredentialResolver). 공백뿐인 값도 없는 것으로 본다(TS는 빈 문자열만). */
public final class EnvironmentCredentials implements CredentialScheme {
  private final Map<String, String> environment;

  public EnvironmentCredentials(Map<String, String> environment) {
    this.environment = Map.copyOf(environment);
  }

  @Override
  public String name() {
    return "env";
  }

  @Override
  public String resolve(String reference) {
    String value = environment.get(reference);
    if (value == null || value.isBlank())
      throw CredentialFailures.invalid("credential environment variable is missing: " + reference);
    return value;
  }
}
