package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("V29는 소스·공간별 문서 소유 목록의 인덱스를 추가한다(P-1)")
class OwnershipIndexMigrationTest {
  @Test
  @DisplayName("소유 목록 조회의 인덱스가 유효하고 준비된 상태다")
  void ownershipIndexIsReadyAndValid() {
    var ready =
        JdbcClient.create(PostgresSupport.dataSource())
            .sql(
                """
        SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'document_spaces_source_space' AND i.indisvalid AND i.indisready
        """)
            .query(Integer.class)
            .single();
    // then
    assertThat(ready).isEqualTo(1);
  }
}
