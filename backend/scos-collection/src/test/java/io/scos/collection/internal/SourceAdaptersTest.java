package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.SourceAdapter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** kind → 수집 어댑터 레지스트리(ADR-0022 §5). */
@DisplayName("수집 어댑터 레지스트리는 kind 하나에 어댑터 하나다(ADR-0022 §5)")
class SourceAdaptersTest {
  final SourceAdapter noop = SourceInstances.NOOP_ADAPTER;

  @Test
  @DisplayName("등록된 kind의 어댑터를 찾는다")
  void findsRegisteredKind() {
    // given
    SourceAdapters adapters = SourceAdapters.of(List.of(noop));

    // when
    Optional<SourceAdapter> found = adapters.forKind(SourceInstances.NOOP_KIND);

    // then
    assertThat(found).containsSame(noop);
  }

  @Test
  @DisplayName("등록되지 않은 kind는 비어 있다")
  void unknownKindIsEmpty() {
    // given
    SourceAdapters adapters = SourceAdapters.of(List.of(noop));

    // when
    Optional<SourceAdapter> found = adapters.forKind("jira");

    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("같은 kind의 어댑터가 둘이면 조립 시점에 거부한다")
  void rejectsDuplicateKind() {
    // when·then
    assertThatThrownBy(() -> SourceAdapters.of(List.of(noop, SourceInstances.adapter(input -> {}))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SourceInstances.NOOP_KIND);
  }

  @Test
  @DisplayName("없는 kind를 요구하면 등록된 kind 목록을 담아 거부한다")
  void requireUnknownKindListsKinds() {
    // given
    SourceAdapters adapters = SourceAdapters.of(List.of(noop));

    // when·then
    assertThatThrownBy(() -> adapters.require("jira"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Source adapter not found: jira. Registered kinds: " + SourceInstances.NOOP_KIND);
  }

  @Test
  @DisplayName("등록된 kind를 이름 순으로 알려 준다")
  void listsKindsInOrder() {
    // given
    SourceAdapters adapters =
        SourceAdapters.of(List.of(SourceInstances.strictAdapter(), SourceInstances.NOOP_ADAPTER));

    // when
    Set<String> kinds = adapters.kinds();

    // then
    assertThat(kinds).containsExactly(SourceInstances.NOOP_KIND, SourceInstances.STRICT_KIND);
  }
}
