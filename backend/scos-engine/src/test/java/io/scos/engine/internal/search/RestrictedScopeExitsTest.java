package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.RestrictedScopeScenario.ALICE;
import static io.scos.engine.internal.search.RestrictedScopeScenario.CAPTURED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.engine.DocumentNotFoundException;
import io.scos.engine.Freshness;
import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ACL을 모르는 제한 범위는 어느 출구에서도 문서를 보지 못한다")
class RestrictedScopeExitsTest {
  @Nested
  @DisplayName("키 없는 Restricted")
  class WithoutKeys extends RestrictedExits {
    @BeforeEach
    void givenScope() {
      scenario = new RestrictedScopeScenario(new AccessScope.Restricted(ALICE, Set.of()));
    }
  }

  @Nested
  @DisplayName("키가 있지만 ACL 행이 하나도 없는 Restricted")
  class WithoutAclRows extends RestrictedExits {
    @BeforeEach
    void givenScope() {
      scenario =
          new RestrictedScopeScenario(
              new AccessScope.Restricted(ALICE, Set.of(AccessKey.parse("user:slack-a:U1"))));
    }
  }

  @DisplayName("제한 범위의 읽기 출구")
  abstract static class RestrictedExits {
    RestrictedScopeScenario scenario;

    @Test
    @DisplayName("검색 후보는 없다")
    void searchHasNoCandidates() {
      // when
      QuestionSearch.Result result = scenario.search();
      // then
      assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("조립 항목은 없다")
    void assemblyHasNoItems() {
      // when
      ContextAssembler.Bundle bundle = scenario.assemble();
      // then
      assertThat(bundle.items()).isEmpty();
    }

    @Test
    @DisplayName("조립은 토큰을 쓰지 않는다")
    void assemblyUsesNoTokens() {
      // when
      ContextAssembler.Bundle bundle = scenario.assemble();
      // then
      assertThat(bundle.usedTokens()).isZero();
    }

    @Test
    @DisplayName("링크 확장은 새 유닛을 들이지 않는다")
    void linksIntroduceNoUnits() {
      // when
      List<String> ids = scenario.expandLinks();
      // then
      assertThat(ids).isSubsetOf("a1");
    }

    @Test
    @DisplayName("읽기는 없는 문서와 같은 오류다")
    void readIsMissing() {
      // given
      String missingMessage = scenario.missingMessage();
      // when·then
      assertThatThrownBy(scenario::read)
          .isInstanceOf(DocumentNotFoundException.class)
          .hasMessage(missingMessage);
    }

    @Test
    @DisplayName("문서 끝을 넘는 offset도 없는 문서 오류다")
    void readBeyondEndIsMissing() {
      // given
      String missingMessage = scenario.missingMessage();
      // when·then
      assertThatThrownBy(scenario::readBeyondEnd)
          .isInstanceOf(DocumentNotFoundException.class)
          .hasMessage(missingMessage);
    }

    @Test
    @DisplayName("관련 문서 조회는 없는 문서와 같은 오류다")
    void relatedIsMissing() {
      // given
      String missingMessage = scenario.missingMessage();
      // when·then
      assertThatThrownBy(scenario::related)
          .isInstanceOf(DocumentNotFoundException.class)
          .hasMessage(missingMessage);
    }

    @Test
    @DisplayName("최신성은 확인할 수 없다")
    void freshnessIsUnavailable() {
      // when
      Freshness result = scenario.freshness();
      // then
      assertThat(result).isEqualTo(Freshness.UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("Unrestricted")
  class Unrestricted {
    RestrictedScopeScenario scenario;

    @BeforeEach
    void givenScope() {
      scenario = new RestrictedScopeScenario(new AccessScope.Unrestricted(ALICE));
    }

    @Test
    @DisplayName("같은 코퍼스에서 검색 후보를 찾는다")
    void searchFindsCandidates() {
      // when
      QuestionSearch.Result result = scenario.search();
      // then
      assertThat(result.candidates()).isNotEmpty();
    }

    @Test
    @DisplayName("같은 코퍼스의 압축 유닛도 검색 후보에 든다")
    void searchFindsCompactedUnit() {
      // when
      QuestionSearch.Result result = scenario.search();
      // then
      assertThat(result.candidates()).extracting(c -> c.unit().id()).contains("c1");
    }

    @Test
    @DisplayName("같은 코퍼스를 조립하면 항목이 있다")
    void assemblyHasItems() {
      // when
      ContextAssembler.Bundle bundle = scenario.assemble();
      // then
      assertThat(bundle.items()).isNotEmpty();
    }

    @Test
    @DisplayName("같은 코퍼스를 조립하면 토큰을 쓴다")
    void assemblyUsesTokens() {
      // when
      ContextAssembler.Bundle bundle = scenario.assemble();
      // then
      assertThat(bundle.usedTokens()).isPositive();
    }

    @Test
    @DisplayName("링크 확장은 같은 코퍼스의 연결된 유닛을 들인다")
    void linksIntroduceLinkedUnit() {
      // when
      List<String> ids = scenario.expandLinks();
      // then
      assertThat(ids).contains("b1");
    }

    @Test
    @DisplayName("같은 문서를 읽을 수 있다")
    void readsDocument() {
      // when
      DocumentReader.Page page = scenario.read();
      // then
      assertThat(page.units()).hasSize(3);
    }

    @Test
    @DisplayName("같은 문서의 관련 문서를 찾는다")
    void findsRelatedDocuments() {
      // when
      List<RelatedDocuments.Related> result = scenario.related();
      // then
      assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("같은 코퍼스의 최신 시각을 알 수 있다")
    void freshnessHasTimestamp() {
      // when
      Freshness result = scenario.freshness();
      // then
      assertThat(result.latestCapturedAt()).contains(CAPTURED_AT);
    }
  }
}
