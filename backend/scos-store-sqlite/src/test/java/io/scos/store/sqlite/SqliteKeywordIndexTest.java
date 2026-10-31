package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.scos.spi.access.AclStore;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.KeywordIndexContract;
import io.scos.spi.store.SearchFilter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("키워드 색인 계약 — SQLite 포트(FTS5 trigram 후보 + 기준 수식)")
class SqliteKeywordIndexTest extends KeywordIndexContract {
  private static final SearchFilter OPEN = new SearchFilter(false, null, AccessScope.singleUser());

  @TempDir Path dir;

  private SqliteIndex sqlite;

  @Override
  protected KeywordIndex newIndex() {
    sqlite = SqliteIndex.open(dir.resolve("index.sqlite"));
    return sqlite.keywords();
  }

  @Override
  protected AclStore aclStore() {
    return sqlite.acl();
  }

  @Test
  @DisplayName("질의 토큰에 큰따옴표가 있어도 예외 없이 그 문자열을 찾는다")
  void quoteInTokenDoesNotThrow() {
    // given
    index.upsert(
        A,
        List.of(
            new IndexedText("q", "d1", "인용", "그는 say\"hello\" 라고 썼다", "/", false),
            new IndexedText("n", "d2", "무관", "전혀 다른 내용", "/", false)));

    // when
    var got = index.search(A, "say\"hello\" \"", 10, OPEN);

    // then
    assertThatCode(() -> index.search(A, "\"\"\" a\"b", 10, OPEN)).doesNotThrowAnyException();
    assertThat(got).extracting(r -> r.unitId()).containsExactly("q");
  }

  @Test
  @DisplayName("교체·삭제한 유닛의 옛 본문은 FTS 색인에서도 사라진다")
  void staleFtsEntriesAreRemoved() {
    // given
    index.upsert(A, List.of(new IndexedText("u", "d1", "옛제목", "정산배치 문서", "/", false)));
    index.upsert(A, List.of(new IndexedText("u", "d1", "새제목", "온보딩 문서", "/", false)));

    // when
    var old = index.search(A, "정산배치", 10, OPEN);
    index.delete(A, List.of("u"));
    var gone = index.search(A, "온보딩", 10, OPEN);

    // then
    assertThat(old).isEmpty();
    assertThat(gone).isEmpty();
  }

  @Test
  @DisplayName("아주 긴 토큰도 식 깊이 한도 없이 검색된다")
  void veryLongTokenWorks() {
    // given
    String longWord = "가나다라마바사".repeat(100);
    index.upsert(A, List.of(new IndexedText("l", "d1", "긴말", longWord, "/", false)));

    // when
    var got = index.search(A, longWord, 10, OPEN);

    // then
    assertThat(got).extracting(r -> r.unitId()).containsExactly("l");
  }
}
