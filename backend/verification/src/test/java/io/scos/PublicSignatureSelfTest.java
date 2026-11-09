package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.scos.engine.admin.boundarysample.PublicSignatureSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공개 시그니처 규칙은 engine 하위 공개 패키지에도 적용된다(R3′)")
class PublicSignatureSelfTest {
  @Test
  @DisplayName("관리 API의 internal·support 누출 표본을 거부한다")
  void catchesPublicSubpackageLeaks() {
    var samples =
        new ClassFileImporter().importClasses(PublicSignatureSamples.class.getDeclaredClasses());
    // when
    var violations =
        PublicBoundaryRulesTest.publicApiExposesNoInternal
            .allowEmptyShould(true)
            .evaluate(samples)
            .getFailureReport()
            .getDetails();
    // then
    assertThat(violations)
        .anyMatch(v -> v.contains("LeaksInternal"))
        .anyMatch(v -> v.contains("LeaksSupport"));
  }

  @Test
  @DisplayName("공개 타입만 노출하는 관리 API 표본은 허용한다")
  void acceptsPublicTypes() {
    var samples = new ClassFileImporter().importClasses(PublicSignatureSamples.PublicOnly.class);
    // when
    var result =
        PublicBoundaryRulesTest.publicApiExposesNoInternal.allowEmptyShould(true).evaluate(samples);
    // then
    assertThat(result.hasViolation()).isFalse();
  }
}
