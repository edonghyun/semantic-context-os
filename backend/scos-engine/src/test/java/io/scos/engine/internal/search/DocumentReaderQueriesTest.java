package io.scos.engine.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("제한 문서 읽기와 related 시드는 페이지를 한 번 조회한다(a11 I-1)")
class DocumentReaderQueriesTest {
  @ParameterizedTest(name = "offset {0}")
  @ValueSource(ints = {0, 20, 10_000_000})
  @DisplayName("제한 읽기는 offset과 무관하게 문서 페이지를 한 번 조회한다")
  void restrictedReadQueriesDocumentOnce(int offset) {
    var document = new ReadableDocument();

    document.readAt(offset);

    // then
    assertThat(document.pageQueries()).isEqualTo(1);
  }

  @Test
  @DisplayName("제한 related의 시드는 문서 페이지를 한 번 조회한다")
  void restrictedRelatedQueriesDocumentOnce() {
    var document = new ReadableDocument();

    document.findRelated();

    // then
    assertThat(document.pageQueries()).isEqualTo(1);
  }

  private static final class ReadableDocument {
    private static final SpaceId SPACE = new SpaceId("read-cost");
    private static final String DOCUMENT = "long-document";
    // 여러 페이지가 생기는 문서. 큰 offset은 문서 끝을 넘어도 조회가 늘지 않는 대조다.
    private static final int UNIT_COUNT = 25;
    private static final int PAGE_SIZE = 1;
    private static final int RELATED_LIMIT = 1;
    private static final AccessKey KEY = AccessKey.parse("user:slack-a:U1");
    private static final AccessScope ACCESS =
        new AccessScope.Restricted(new Principal("member"), Set.of(KEY));
    private static final Instant OBSERVED = Instant.parse("2026-10-25T00:00:00Z");
    private final CountingUnitStore counted;
    private final DocumentReader reader;
    private final RelatedDocuments related;

    ReadableDocument() {
      var acl = new MemoryAclStore();
      var units = new MemoryUnitStore(acl);
      var vectors = new MemoryVectorIndex(acl);
      var maintenance = new MemoryUnitMaintenance(units, vectors);
      maintenance.upsert(
          SPACE,
          IntStream.range(0, UNIT_COUNT)
              .mapToObj(i -> ContextUnits.unit("paragraph-" + i).inDocument(DOCUMENT).build())
              .toList());
      acl.put(
          SPACE, List.of(new DocumentAcl(DOCUMENT, DocumentAcl.Status.OK, Set.of(KEY), OBSERVED)));
      counted = new CountingUnitStore(units);
      reader = new DocumentReader(counted);
      related = new RelatedDocuments(counted, vectors);
    }

    void readAt(int offset) {
      reader.read(ACCESS, SPACE, DOCUMENT, offset, PAGE_SIZE);
    }

    void findRelated() {
      related.find(ACCESS, SPACE, DOCUMENT, RELATED_LIMIT);
    }

    int pageQueries() {
      return counted.documentReads();
    }
  }
}
