package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.collection.internal.TsRawViews.ContentView;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 공용 TS 비교 값(TsRawViews)의 비교 규칙: inline 본문은 JS 순회 순서 바이트, 시각은 TS 모양, errorKind는 제품 매핑. */
@DisplayName("공용 TS 비교 값(TsRawViews)은 TS가 남긴 값과 같은 규칙으로 비교한다")
class TsRawViewsTest {
  static final ObjectMapper JSON = new ObjectMapper();

  /** TS fixture의 inline 본문(파일은 JS 순서로 쓰여 정수 같은 키가 앞이다). */
  static final String TS_CONTENT =
      "{\"storage\":\"inline\",\"mediaType\":\"application/json\",\"value\":{\"10\":1,\"b\":2}}";

  @Test
  @DisplayName("inline 본문은 JS 순서로 쓴 바이트가 같아야 같다")
  void inlineComparesJsOrderedBytes() {
    // when
    ContentView java =
        ContentView.of(new RawContent.Inline("application/json", "{\"b\":2,\"10\":1}"));

    // then
    assertThat(java).isNotEqualTo(ContentView.expected(JSON.readTree(TS_CONTENT)));
  }

  @Test
  @DisplayName("JS 순서로 쓴 바이트가 같은 inline 본문은 같다")
  void inlineWithJsOrderedBytesIsEqual() {
    // when
    ContentView java =
        ContentView.of(new RawContent.Inline("application/json", "{\"10\":1,\"b\":2}"));

    // then
    assertThat(java).isEqualTo(ContentView.expected(JSON.readTree(TS_CONTENT)));
  }

  @Test
  @DisplayName("시각은 TS toISOString처럼 밀리초 세 자리다")
  void isoHasMillis() {
    // when
    String iso = TsRawViews.iso(Instant.parse("2026-10-15T00:00:00Z"));

    // then
    assertThat(iso).isEqualTo("2026-10-15T00:00:00.000Z");
  }

  @Test
  @DisplayName("run errorKind는 제품 매핑을 따른다")
  void runErrorKindUsesProductMapping() {
    // when
    String kind =
        TsRawViews.runErrorKind(
            new CallFailureException(
                "x", CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, "no")));

    // then
    assertThat(kind).isEqualTo("permission_denied");
  }

  /** 실제값 쪽 어휘 lastItems(어댑터 fixture 테스트가 Java 페이지에서 남을 active 원본을 만든다). */
  @Nested
  @DisplayName("페이지를 차례로 적용해 남을 원본을 고르면")
  class LastItems {
    static final Checkpoint CHECKPOINT = new Checkpoint(1, "{}");

    static CollectionPage page(RawMutation... mutations) {
      return new CollectionPage(List.of(mutations), List.of(), CHECKPOINT);
    }

    static RawMutation.Upsert upsert(String id, String version) {
      return RawItems.item(id).version(version).inlineJson("{}").upsert();
    }

    static RawMutation.Delete delete(String id) {
      return new RawMutation.Delete(
          id, Optional.empty(), Optional.empty(), Optional.empty(), RawItems.T0);
    }

    @Test
    @DisplayName("뒤 페이지에서 delete된 id는 빠진다")
    void deletedIdIsDropped() {
      // given
      List<CollectionPage> pages =
          List.of(page(upsert("a", "v1"), upsert("b", "v1")), page(delete("a")));

      // when
      List<TsRawViews.ItemView> last = TsRawViews.lastItems(pages);

      // then
      assertThat(last).extracting(TsRawViews.ItemView::externalId).containsExactly("b");
    }

    @Test
    @DisplayName("같은 id를 다시 upsert하면 마지막 원본 하나만 남는다")
    void reUpsertKeepsLastVersion() {
      // given
      List<CollectionPage> pages = List.of(page(upsert("a", "v1")), page(upsert("a", "v2")));

      // when
      List<TsRawViews.ItemView> last = TsRawViews.lastItems(pages);

      // then
      assertThat(last)
          .extracting(TsRawViews.ItemView::externalId, TsRawViews.ItemView::sourceVersion)
          .containsExactly(tuple("a", "v2"));
    }

    @Test
    @DisplayName("delete 뒤 다시 upsert한 id는 남는다")
    void upsertAfterDeleteRevives() {
      // given
      List<CollectionPage> pages =
          List.of(page(upsert("a", "v1")), page(delete("a")), page(upsert("a", "v3")));

      // when
      List<TsRawViews.ItemView> last = TsRawViews.lastItems(pages);

      // then
      assertThat(last)
          .extracting(TsRawViews.ItemView::externalId, TsRawViews.ItemView::sourceVersion)
          .containsExactly(tuple("a", "v3"));
    }
  }

  /** 비교 뷰가 필드를 빠뜨리면 Java 값과 TS 기대가 그 필드에서 달라도 같다고 나온다. 같은 원본(대조군)에서 필드 하나만 바꾼 TS 기대는 같지 않아야 한다. */
  @Nested
  @DisplayName("원본 뷰(ItemView)로 Java 원본과 TS 원본을 비교하면")
  class ItemViewComparison {
    static final RawItem JAVA =
        new RawItem(
            "msg-1",
            "slack.message",
            "v1",
            Optional.of(new SourceOrder("slack.message-state.v1", "0001")),
            Optional.of("2026-10-14T00:00:00.000Z"),
            Optional.of("2026-10-14T00:00:01.000Z"),
            Instant.parse("2026-10-14T00:00:02Z"),
            "{\"a\":1}",
            Optional.of(new RawContent.Inline("application/json", "{\"text\":\"hi\"}")));

    /** JAVA와 같은 TS 원본 JSON의 필드들(바꿀 필드 하나를 덮어쓴다). */
    static String tsItem(String field, String json) {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("key", "{\"sourceInstanceId\":\"s\",\"externalId\":\"msg-1\"}");
      fields.put("resourceType", "\"slack.message\"");
      fields.put("sourceVersion", "\"v1\"");
      fields.put("sourceOrder", "{\"scheme\":\"slack.message-state.v1\",\"position\":\"0001\"}");
      fields.put("sourceCreatedAt", "\"2026-10-14T00:00:00.000Z\"");
      fields.put("sourceUpdatedAt", "\"2026-10-14T00:00:01.000Z\"");
      fields.put("metadata", "{\"a\":1}");
      fields.put(
          "content",
          "{\"storage\":\"inline\",\"mediaType\":\"application/json\",\"value\":{\"text\":\"hi\"}}");
      fields.put("observedAt", "\"2026-10-14T00:00:02.000Z\"");
      if (field != null) fields.put(field, json);
      StringBuilder out = new StringBuilder("{");
      fields.forEach(
          (k, v) ->
              out.append(out.length() > 1 ? "," : "")
                  .append('"')
                  .append(k)
                  .append("\":")
                  .append(v));
      return out.append('}').toString();
    }

    @Test
    @DisplayName("대조군: 모든 필드가 같으면 같다")
    void equalWhenAllFieldsMatch() {
      // when
      TsRawViews.ItemView expected =
          TsRawViews.ItemView.expected(JSON.readTree(tsItem(null, null)));

      // then
      assertThat(TsRawViews.ItemView.of(JAVA)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "key|{\"sourceInstanceId\":\"s\",\"externalId\":\"msg-2\"}",
          "resourceType|\"slack.file\"",
          "sourceVersion|\"v2\"",
          "sourceOrder|{\"scheme\":\"slack.message-state.v1\",\"position\":\"0002\"}",
          "sourceCreatedAt|\"2026-10-14T09:00:00.000Z\"",
          "sourceUpdatedAt|null",
          "metadata|{\"a\":2}",
          "content|{\"storage\":\"inline\",\"mediaType\":\"application/json\",\"value\":{\"text\":\"bye\"}}",
          "observedAt|\"2026-10-14T00:00:03.000Z\""
        })
    @DisplayName("필드 하나만 달라도 같지 않다")
    void differsOnEachField(String field, String json) {
      // when
      TsRawViews.ItemView expected =
          TsRawViews.ItemView.expected(JSON.readTree(tsItem(field, json)));

      // then
      assertThat(TsRawViews.ItemView.of(JAVA)).isNotEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("run 보고 뷰(ReportView)로 비교하면")
  class ReportViewComparison {
    static final String TS_REPORT =
        "{\"status\":\"succeeded\",\"pagesApplied\":1,\"upserted\":2,\"deleted\":3,\"unchanged\":4,\"rejected\":5,\"errorCount\":6}";

    @Test
    @DisplayName("대조군: 필드마다 다른 값인 Java 보고와 같은 TS 보고는 같다")
    void javaReportMatchesSameTsReport() {
      // given: 필드마다 다른 값 — 필드를 서로 바꿔 옮기면 드러난다
      CollectionRuntime.Report java =
          new CollectionRuntime.Report(
              new RunId("run-1"),
              new SourceInstanceId("s"),
              RunStatus.SUCCEEDED,
              1,
              2,
              3,
              4,
              5,
              6,
              Optional.empty(),
              Optional.empty());

      // when
      TsRawViews.ReportView view = TsRawViews.ReportView.of(java);

      // then
      assertThat(view).isEqualTo(TsRawViews.ReportView.expected(JSON.readTree(TS_REPORT)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "status, failed",
      "pagesApplied, 9",
      "upserted, 9",
      "deleted, 9",
      "unchanged, 9",
      "rejected, 9",
      "errorCount, 9"
    })
    @DisplayName("필드 하나만 달라도 같지 않다")
    void differsOnEachField(String field, String value) {
      // given
      var base = (ObjectNode) JSON.readTree(TS_REPORT);
      var changed = base.deepCopy();
      if (field.equals("status")) changed.put(field, value);
      else changed.put(field, Integer.parseInt(value));

      // when
      TsRawViews.ReportView expected = TsRawViews.ReportView.expected(changed);

      // then
      assertThat(TsRawViews.ReportView.expected(base)).isNotEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("거부 뷰(RejectionView)로 Java 거부와 TS 거부를 비교하면")
  class RejectionViewComparison {
    static final RejectedItem JAVA =
        new RejectedItem(
            RejectedItem.FailureKind.INVALID_ITEM,
            "no ts",
            Optional.of("{\"channel\":\"C1\",\"index\":2}"),
            Instant.parse("2026-10-14T00:00:02Z"),
            Optional.empty());

    static final String TS_REJECTION =
        "{\"failureKind\":\"invalid_item\",\"message\":\"no ts\",\"providerLocator\":{\"channel\":\"C1\",\"index\":2},\"observedAt\":\"2026-10-14T00:00:02.000Z\"}";

    @Test
    @DisplayName("대조군: 모든 필드가 같으면 같다")
    void equalWhenAllFieldsMatch() {
      // when
      TsRawViews.RejectionView expected =
          TsRawViews.RejectionView.expected(JSON.readTree(TS_REJECTION));

      // then
      assertThat(TsRawViews.RejectionView.of(JAVA)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "failureKind|\"unsupported_item\"",
          "message|\"other\"",
          "providerLocator|{\"index\":2,\"channel\":\"C1\"}",
          "observedAt|\"2026-10-14T00:00:03.000Z\""
        })
    @DisplayName("필드 하나만 달라도 같지 않다(위치는 키 순서까지)")
    void differsOnEachField(String field, String json) {
      // given
      ObjectNode changed = (ObjectNode) JSON.readTree(TS_REJECTION);
      changed.set(field, JSON.readTree(json));

      // when
      TsRawViews.RejectionView expected = TsRawViews.RejectionView.expected(changed);

      // then
      assertThat(TsRawViews.RejectionView.of(JAVA)).isNotEqualTo(expected);
    }
  }
}
