package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("접근 입력의 저장 콘텐츠 조회는 소스의 active 원본만 읽는다 (T-b17)")
class AccessStoredContentTest {
  @ParameterizedTest
  @ValueSource(strings = {"inline", "blob"})
  @DisplayName("inline·blob 저장 JSON을 접근 수집에 제공한다")
  void activeCarrierContentIsAvailable(String storage) {
    try (var s = new AccessCollectionScenario("jira")) {
      // given
      s.storedIssueContent(storage);
      s.collect();
      // when
      var content = s.storedContent("issue:10001");
      // then
      assertThat(content).contains("{\"id\":\"10001\",\"fields\":{\"project\":{\"id\":\"P1\"}}}");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "deleted", "otherSource", "missingBlob"})
  @DisplayName("없거나 삭제된 원본·다른 소스·누락 blob은 콘텐츠를 제공하지 않는다")
  void unavailableContentIsEmpty(String storage) {
    try (var s = new AccessCollectionScenario("jira")) {
      // given
      s.storedIssueContent(storage);
      s.collect();
      // when
      var content = s.storedContent("issue:10001");
      // then
      assertThat(content).isEmpty();
    }
  }

  @Test
  @DisplayName("점유를 잃은 뒤 저장 콘텐츠는 읽을 수 없다")
  void claimLossPreventsStoredContentRead() {
    try (var s = new AccessCollectionScenario("jira")) {
      // given
      s.storedIssueContent("inline");
      s.collect();
      s.takeover();
      // when
      var failure = catchThrowable(() -> s.storedContent("issue:10001"));
      // then
      assertThat(failure).isInstanceOf(ClaimLostException.class);
    }
  }
}
