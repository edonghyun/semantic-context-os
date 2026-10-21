package io.scos.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.ContextUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공개 데모 코퍼스(demo/)의 파생 파일은 마크다운 원본과 어긋나지 않고, 판정셋은 코퍼스 안의 문서만 가리킨다. 어긋나면 {@code DemoCorpus.main}으로
 * 다시 만든다(demo/README.md "Editing the corpus").
 */
@DisplayName("데모 코퍼스의 적재 파일·판정셋은 마크다운 원본과 같다")
class DemoCorpusTest {
  static final DemoCorpus CORPUS = DemoCorpus.read(DemoCorpus.defaultRoot());

  static Set<String> documentIds() {
    return CORPUS.documents().stream()
        .map(DemoCorpus.Document::documentId)
        .collect(Collectors.toSet());
  }

  static List<String> expectedDocumentIds() {
    return StreamSupport.stream(CORPUS.queries().spliterator(), false)
        .flatMap(q -> StreamSupport.stream(q.get("expectedDocumentIds").spliterator(), false))
        .map(id -> id.asString())
        .toList();
  }

  @Test
  @DisplayName("커밋된 units.json은 원본에서 다시 만든 것과 같다")
  void unitsFileIsUpToDate() {
    // then
    assertThat(CORPUS.committed(DemoCorpus.UNITS_FILE)).isEqualTo(CORPUS.unitsJson());
  }

  @Test
  @DisplayName("커밋된 판정셋 pack은 원본 문서·질의에서 다시 만든 것과 같다")
  void packFileIsUpToDate() {
    // then
    assertThat(CORPUS.committed(DemoCorpus.PACK_FILE)).isEqualTo(CORPUS.packJson());
  }

  @Test
  @DisplayName("문서는 40–80개다")
  void corpusSize() {
    // then
    assertThat(CORPUS.documents()).hasSizeBetween(40, 80);
  }

  @Test
  @DisplayName("문서는 Slack·Notion·Jira 세 출처에서 온다")
  void threeSources() {
    // then
    assertThat(CORPUS.documents())
        .extracting(DemoCorpus.Document::source)
        .containsOnly("slack", "notion", "jira")
        .contains("slack", "notion", "jira");
  }

  @Test
  @DisplayName("판정 질의는 10–20개다")
  void judgmentSize() {
    // then
    assertThat(CORPUS.queries()).hasSizeBetween(10, 20);
  }

  @Test
  @DisplayName("판정 질의의 정답 문서는 모두 코퍼스에 있다")
  void judgmentsPointIntoCorpus() {
    // then
    assertThat(documentIds()).containsAll(expectedDocumentIds());
  }

  @Test
  @DisplayName("문서 사이 링크는 모두 코퍼스 안의 문서를 가리킨다")
  void linksResolve() {
    // when
    List<String> targets = CORPUS.units().stream().flatMap(u -> u.links().stream()).toList();

    // then
    assertThat(targets).isNotEmpty();
    assertThat(documentIds()).containsAll(targets);
  }

  @Test
  @DisplayName("문서마다 유닛이 하나 이상 나온다")
  void everyDocumentProjects() {
    // then
    assertThat(CORPUS.units().stream().map(ContextUnit::documentId).distinct())
        .hasSameSizeAs(CORPUS.documents());
  }
}
