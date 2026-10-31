package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scosrules.ClientBoundarySamples;

/** 클라이언트 경계 규칙(R4) 자체의 계약: 비공개 패키지 의존 표본을 잡고 공개 패키지 표본은 놓아둔다. */
@DisplayName("클라이언트 경계 규칙은 비공개 패키지 의존을 잡는다(R4)")
class ClientBoundarySelfTest {
  static final JavaClasses SAMPLES =
      new ClassFileImporter().importClasses(ClientBoundarySamples.class.getDeclaredClasses());

  static List<String> violations() {
    return PublicBoundaryRulesTest.onlyPublicPackages("scosrules..")
        .evaluate(SAMPLES)
        .getFailureReport()
        .getDetails();
  }

  @Test
  @DisplayName("engine.support·mcp.wire·engine.internal 의존 표본을 잡는다")
  void catchesNonPublicPackages() {
    // when
    List<String> found = violations();

    // then
    assertThat(found)
        .anyMatch(v -> v.contains("ViolatesEngineSupport"))
        .anyMatch(v -> v.contains("ViolatesMcpWire"))
        .anyMatch(v -> v.contains("ViolatesEngineInternal"));
  }

  @Test
  @DisplayName("공개 패키지만 쓰는 표본은 놓아둔다")
  void passesPublicPackages() {
    // when
    List<String> found = violations();

    // then
    assertThat(found).noneMatch(v -> v.contains("PassesPublicPackages"));
  }
}
