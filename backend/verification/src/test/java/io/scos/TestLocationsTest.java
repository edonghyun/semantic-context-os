package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.Location;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("게시 계약 키트는 위치와 관계없이 테스트 코드다 (TESTING §13)")
class TestLocationsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:/build/scos-board-archive-testkit/build/classes/java/main/",
        "jar:file:/build/scos-board-archive-testkit.jar!/",
        "jar:file:/cache/scos-board-archive-testkit-0.1.0-local.jar!/",
        "file:/build/scos-future-testkit/build/classes/java/main/",
        "jar:file:/build/scos-future-testkit.jar!/",
        "jar:file:/cache/scos-future-testkit-0.1.0-local.jar!/"
      })
  @DisplayName("계약 키트에는 테스트 규칙을 적용한다")
  void testkitIsTestCode(String uri) {
    // when
    boolean included = new TestLocations.TestCode().includes(Location.of(URI.create(uri)));
    // then
    assertThat(included).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:/build/scos-board-archive-testkit/build/classes/java/main/",
        "jar:file:/build/scos-board-archive-testkit.jar!/",
        "jar:file:/cache/scos-board-archive-testkit-0.1.0-local.jar!/",
        "file:/build/scos-future-testkit/build/classes/java/main/",
        "jar:file:/build/scos-future-testkit.jar!/",
        "jar:file:/cache/scos-future-testkit-0.1.0-local.jar!/"
      })
  @DisplayName("계약 키트는 제품 규칙에서 제외한다")
  void testkitIsNotProductCode(String uri) {
    // when
    boolean included = new TestLocations.ProductCode().includes(Location.of(URI.create(uri)));
    // then
    assertThat(included).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:/build/scos-board-archive/build/classes/java/main/",
        "jar:file:/build/scos-board-archive.jar!/"
      })
  @DisplayName("제품 모듈에는 제품 규칙을 계속 적용한다")
  void archiveIsProductCode(String uri) {
    // when
    boolean included = new TestLocations.ProductCode().includes(Location.of(URI.create(uri)));
    // then
    assertThat(included).isTrue();
  }
}
