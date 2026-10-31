package io.scos.store.sqlite;

import static io.scos.store.sqlite.SqlParts.aclFilter;
import static io.scos.store.sqlite.SqlParts.bindKeys;
import static io.scos.store.sqlite.SqlParts.json;

import io.scos.engine.support.text.KeywordScore;
import io.scos.engine.support.text.Text;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.transaction.support.TransactionOperations;

/**
 * SQLite 키워드 색인(V1 {@code units_text} + trigram FTS5 {@code unit_text}). 후보는 FTS5(3글자 이상 토큰: 토큰
 * trigram의 OR)와 {@code instr}(3글자 미만 토큰: 전체 훑기)의 합집합이고, 공간·archived·partition·ACL은 SQL WHERE로 거른 뒤
 * Java에서 기준 수식({@link KeywordScore})으로 점수를 매겨 top-k를 자른다(동점은 seq 순). 질의는 SQL에 이어 붙이지 않고 바인드 파라미터로만
 * 넘긴다. 모든 SQL이 space_id 조건을 가진다.
 */
public final class SqliteKeywordIndex implements KeywordIndex {
  /** FTS5 식 깊이 한도(256)를 넘지 않게 한 MATCH 식에 OR로 묶는 trigram 수. */
  private static final int TRIGRAMS_PER_MATCH = 100;

  private final JdbcClient jdbc;
  private final TransactionOperations tx;

  public SqliteKeywordIndex(JdbcClient jdbc, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public IndexCapabilities capabilities() {
    return IndexCapabilities.exactTransactional();
  }

  @Override
  public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
    List<String> tokens =
        Text.splitWhitespace(text).stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    if (tokens.isEmpty()) return List.of();
    KeywordScore scorer = KeywordScore.of(tokens);
    List<ScoredRef> hits = new ArrayList<>();
    for (Row row : candidates(space, tokens, filter)) {
      double score = scorer.score(row.text(), row.compact());
      if (score > 0) hits.add(new ScoredRef(row.unitId(), row.documentId(), score));
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score())); // 안정 정렬 — 동점은 seq 순
    return List.copyOf(hits.size() > k ? hits.subList(0, k) : hits);
  }

  private List<Row> candidates(SpaceId space, List<String> tokens, SearchFilter filter) {
    boolean restricted = filter.access() instanceof AccessScope.Restricted;
    List<String> matches = new ArrayList<>();
    List<String> shorts = new ArrayList<>();
    for (String token : tokens) collect(token, matches, shorts);
    StatementSpec statement =
        jdbc.sql(
                "SELECT t.unit_id, t.document_id, t.text, t.compact FROM units_text t"
                    + " WHERE t.space_id = :space AND ("
                    + anyMatch(matches.size(), shorts.size())
                    + ")"
                    + (filter.includeArchived() ? "" : " AND t.archived = 0")
                    + (filter.partitions() == null
                        ? ""
                        : " AND t.partition IN (SELECT value FROM json_each(:partitions))")
                    + (restricted ? aclFilter("t.document_id") : "")
                    + " ORDER BY t.seq")
            .param("space", space.value());
    for (int i = 0; i < matches.size(); i++) statement = statement.param("m" + i, matches.get(i));
    for (int i = 0; i < shorts.size(); i++) statement = statement.param("s" + i, shorts.get(i));
    if (filter.partitions() != null)
      statement = statement.param("partitions", json(filter.partitions()));
    return bindKeys(statement, filter.access())
        .query(
            (rs, i) ->
                new Row(
                    rs.getString("unit_id"),
                    rs.getString("document_id"),
                    rs.getString("text"),
                    rs.getString("compact")))
        .list();
  }

  /** 3글자 이상 토큰은 MATCH 식(들)으로, 미만은 instr 인자로 나눈다. */
  private static void collect(String token, List<String> matches, List<String> shorts) {
    List<String> trigrams = Text.trigrams(token);
    if (trigrams.isEmpty()) shorts.add(token);
    for (int i = 0; i < trigrams.size(); i += TRIGRAMS_PER_MATCH)
      matches.add(
          matchExpression(trigrams.subList(i, Math.min(i + TRIGRAMS_PER_MATCH, trigrams.size()))));
  }

  /** 후보 합집합 조건 — 자리표시자 이름(:m0.., :s0..)만 이어 붙이고 질의 값은 바인드한다. */
  private static String anyMatch(int matches, int shorts) {
    Stream<String> trigrams =
        IntStream.range(0, matches)
            .mapToObj(
                i -> "t.seq IN (SELECT rowid FROM unit_text WHERE unit_text MATCH :m" + i + ")");
    Stream<String> shortTokens =
        IntStream.range(0, shorts).mapToObj(i -> "instr(t.compact, :s" + i + ") > 0");
    return String.join(" OR ", Stream.concat(trigrams, shortTokens).toList());
  }

  /** trigram마다 FTS5 문자열(큰따옴표는 두 번 써서 이스케이프)로 만들어 OR로 잇는다. */
  private static String matchExpression(List<String> trigrams) {
    return String.join(
        " OR ", trigrams.stream().map(g -> "\"" + g.replace("\"", "\"\"") + "\"").toList());
  }

  /** 호출자의 트랜잭션에 참여하고, 없으면 한 트랜잭션으로 묶는다. 기존 unitId는 seq를 유지한 채 덮어쓴다(FTS 항목도 함께 갱신). */
  @Override
  public void upsert(SpaceId space, List<IndexedText> units) {
    if (units.isEmpty()) return;
    tx.executeWithoutResult(
        status -> {
          for (IndexedText u : units) {
            String text = (u.title() + "\n" + u.content()).toLowerCase(Locale.ROOT);
            String compact = Text.stripWhitespace(text);
            removeFts(space, List.of(u.unitId()));
            jdbc.sql(
                    """
                    INSERT INTO units_text (space_id, unit_id, document_id, partition, archived, text, compact)
                    VALUES (:space, :unit, :doc, :partition, :archived, :text, :compact)
                    ON CONFLICT (space_id, unit_id) DO UPDATE SET
                      document_id = excluded.document_id, partition = excluded.partition,
                      archived = excluded.archived, text = excluded.text, compact = excluded.compact
                    """)
                .param("space", space.value())
                .param("unit", u.unitId())
                .param("doc", u.documentId())
                .param("partition", u.partition())
                .param("archived", u.archived() ? 1 : 0)
                .param("text", text)
                .param("compact", compact)
                .update();
            jdbc.sql(
                    "INSERT INTO unit_text (rowid, compact)"
                        + " SELECT seq, compact FROM units_text WHERE space_id = :space AND unit_id = :unit")
                .param("space", space.value())
                .param("unit", u.unitId())
                .update();
          }
        });
  }

  @Override
  public void delete(SpaceId space, List<String> unitIds) {
    if (unitIds.isEmpty()) return;
    tx.executeWithoutResult(
        status -> {
          removeFts(space, unitIds);
          jdbc.sql(
                  "DELETE FROM units_text WHERE space_id = :space"
                      + " AND unit_id IN (SELECT value FROM json_each(:ids))")
              .param("space", space.value())
              .param("ids", json(unitIds))
              .update();
        });
  }

  /** 외부 콘텐츠 FTS5는 자동 동기화가 없다 — 본문 행을 바꾸거나 지우기 전에 현재 색인 항목을 'delete' 명령으로 뺀다. */
  private void removeFts(SpaceId space, List<String> unitIds) {
    jdbc.sql(
            "INSERT INTO unit_text (unit_text, rowid, compact)"
                + " SELECT 'delete', seq, compact FROM units_text WHERE space_id = :space"
                + " AND unit_id IN (SELECT value FROM json_each(:ids))")
        .param("space", space.value())
        .param("ids", json(unitIds))
        .update();
  }

  /** 후보 행: 점수 계산에 쓰는 본문 두 열. */
  private record Row(String unitId, String documentId, String text, String compact) {}
}
