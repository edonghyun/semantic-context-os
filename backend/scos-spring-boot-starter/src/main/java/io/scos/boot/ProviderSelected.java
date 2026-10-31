package io.scos.boot;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 공급자 선택 조건({@link ConditionalOnProvider}): 설정 키를 읽어 비었거나 공백이면 그 역할의 Properties 기본
 * 공급자(DEFAULT_PROVIDER)로 보고 비교한다. 빈 환경변수(예: {@code SCOS_EMBEDDER_PROVIDER=})가 바인딩된 설정(기본값으로 정규화)과
 * 다른 공급자를 고르지 않게, 기본값은 이 상수 하나뿐이다(matchIfMissing을 따로 두지 않는다).
 */
class ProviderSelected extends SpringBootCondition {
  /** 공급자를 고르는 역할: 설정 키와 기본 공급자. */
  enum Role {
    EMBEDDER("scos.embedder.provider", EmbedderProperties.DEFAULT_PROVIDER),
    RERANK("scos.rerank.provider", RerankProperties.DEFAULT_PROVIDER);

    final String property;
    final String defaultProvider;

    Role(String property, String defaultProvider) {
      this.property = property;
      this.defaultProvider = defaultProvider;
    }
  }

  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    // 이 조건은 @ConditionalOnProvider가 붙은 곳에서만 불리고 role은 필수 속성이다 — requireNonNull은 실패하지 않는다
    var attributes =
        Objects.requireNonNull(
            metadata.getAnnotationAttributes(ConditionalOnProvider.class.getName()));
    Role role = (Role) Objects.requireNonNull(attributes.get("role"));
    String expected = (String) attributes.get("value");
    String bound =
        Binder.get(context.getEnvironment())
            .bind(role.property, String.class)
            .map(String::strip)
            .orElse("");
    String selected = bound.isEmpty() ? role.defaultProvider : bound;
    String message = role.property + "=" + selected;
    return selected.equals(expected)
        ? ConditionOutcome.match(message)
        : ConditionOutcome.noMatch(message + " (expected " + expected + ")");
  }
}
