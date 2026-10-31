package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.SourceInstanceId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("접근 설정은 모드와 ACL 수집 정책을 바인딩한다")
class AccessPropertiesTest {
  private AccessProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bindOrCreate("scos.access", AccessProperties.class);
  }

  @Test
  @DisplayName("모르는 모드 값은 바인딩을 거부한다")
  void unknownModeIsRejected() {
    // when·then
    assertThatThrownBy(() -> bind(Map.of("scos.access.mode", "unknown")))
        .isInstanceOf(BindException.class)
        .hasMessageContaining("scos.access.mode");
  }

  @Test
  @DisplayName("다중 사용자 모드는 ACL 수집이 늘 켜져 있다")
  void multiUserCollectsAccess() {
    // when
    var properties = bind(Map.of("scos.access.mode", "multi-user"));

    // then
    assertThat(properties.collectAccess()).isTrue();
  }

  @Test
  @DisplayName("단일 사용자 모드도 수집을 켜면 ACL을 수집한다")
  void singleUserCanEnableCollection() {
    // when
    var properties = bind(Map.of("scos.access.collect", "true"));

    // then
    assertThat(properties.collectAccess()).isTrue();
  }

  @Test
  @DisplayName("단일 사용자 모드의 ACL 수집 기본값은 끔이다")
  void singleUserDoesNotCollectByDefault() {
    // when
    var properties = bind(Map.of());

    // then
    assertThat(properties.collectAccess()).isFalse();
  }

  @Test
  @DisplayName("다중 사용자 모드에서 수집을 끄도록 명시하면 거부한다")
  void multiUserCannotDisableCollection() {
    // when·then
    assertThatThrownBy(
            () -> bind(Map.of("scos.access.mode", "multi-user", "scos.access.collect", "false")))
        .isInstanceOf(BindException.class)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("scos.access.collect=false is not allowed in multi-user mode");
  }

  @Test
  @DisplayName("도메인 설정은 소문자 정책으로 바뀐다")
  void domainsAreLowercased() {
    // when
    var properties =
        bind(
            Map.of(
                "scos.access.org-domains[0]",
                "EXAMPLE.COM",
                "scos.access.instance-domains.drive-a",
                "EXAMPLE.COM"));

    // then
    assertThat(properties.domainPolicy().orgDomains()).containsExactly("example.com");
    assertThat(properties.domainPolicy().instanceDomains())
        .containsExactlyEntriesOf(Map.of(new SourceInstanceId("drive-a"), "example.com"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"drive.a", "drive:a", "drive%a"})
  @DisplayName("점·콜론·퍼센트가 든 인스턴스 도메인 키는 거부한다")
  void invalidInstanceIdsAreRejected(String instance) {
    // when·then
    assertThatThrownBy(
            () -> bind(Map.of("scos.access.instance-domains.[" + instance + "]", "example.com")))
        .isInstanceOf(BindException.class)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("invalid scos.access.instance-domains instance id");
  }

  @Test
  @DisplayName("빈 수집 설정은 모드의 기본값을 따른다")
  void emptyCollectionUsesModeDefault() {
    // when
    var properties = bind(Map.of("scos.access.mode", "multi-user", "scos.access.collect", ""));

    // then
    assertThat(properties.collectAccess()).isTrue();
  }

  @Test
  @DisplayName("단일 사용자 모드는 명시적으로 수집을 끌 수 있다")
  void singleUserCanDisableCollection() {
    // when
    var properties = bind(Map.of("scos.access.collect", "false"));

    // then
    assertThat(properties.collectAccess()).isFalse();
  }

  @Test
  @DisplayName("인스턴스 도메인이 없으면 domain 키를 허용하지 않는다")
  void absentInstanceDomainIsDenied() {
    // when
    var properties = bind(Map.of("scos.access.org-domains[0]", "example.com"));

    // then
    assertThat(
            properties.domainPolicy().admits("alice@example.com", new SourceInstanceId("drive-a")))
        .isFalse();
  }

  @Test
  @DisplayName("괄호 없는 점 구분 인스턴스 도메인 키도 거부한다")
  void dottedInstanceIdIsRejectedWithoutBrackets() {
    // when·then
    assertThatThrownBy(() -> bind(Map.of("scos.access.instance-domains.drive.a", "example.com")))
        .isInstanceOf(BindException.class)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("invalid scos.access.instance-domains instance id");
  }

  @Test
  @DisplayName("설정이 없으면 단일 사용자 모드다")
  void defaultModeIsSingleUser() {
    // when
    var properties = bind(Map.of());

    // then
    assertThat(properties.mode()).isEqualTo(AccessProperties.Mode.SINGLE_USER);
  }
}
