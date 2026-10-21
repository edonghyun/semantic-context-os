package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstances;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.ListingField;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 소스 목록의 어댑터 고유 필드(stage2 §2.4 {@link ListingField}): 어댑터는 JSON 원문을 내고, 목록은 그것을 읽어 항목 끝에 순서대로 붙인다.
 * Slack의 실제 필드 바이트는 {@code QueryCommandsTsEquivalenceTest}가 본다.
 */
@DisplayName("소스 목록은 어댑터 필드 JSON을 순서대로 붙이고 JSON이 아니면 거부한다")
class SourceListingFieldsJsonTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant AT = Instant.parse("2026-10-18T01:02:03.456Z");
  static final SourceInstance SOURCE =
      SourceInstances.of(new SourceInstanceId("fake-main"), "fake", "{}", "env:FAKE_TOKEN", AT);

  final SourceQueryJson render = new SourceQueryJson(JSON);

  /** 목록 필드만 내는 가짜 어댑터(kind fake, 수집하지 않는다). */
  static SourceAdapters listing(ListingField... fields) {
    SourceAdapter adapter =
        new SourceAdapter() {
          @Override
          public String kind() {
            return "fake";
          }

          @Override
          public void validateConfig(SourceConfig config) {}

          @Override
          public CollectionCoverage coverage(
              SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
            return CollectionCoverage.INCREMENTAL;
          }

          @Override
          public void collect(CollectionInput input, PageSink sink) {}

          @Override
          public List<ListingField> listingFields(SourceConfig config) {
            return List.of(fields);
          }
        };
    return SourceAdapters.of(List.of(adapter));
  }

  static List<String> keys(JsonNode item) {
    List<String> out = new ArrayList<>();
    item.propertyNames().forEach(out::add);
    return out;
  }

  @Nested
  @DisplayName("어댑터가 목록 필드 둘을 이름 역순으로 내면")
  class TwoFields {
    @Test
    @DisplayName("목록 항목 끝에 어댑터가 낸 순서대로 붙는다")
    void appendsInListOrder() {
      // given
      SourceAdapters adapters =
          listing(
              new ListingField("zeta", "{\"b\":1,\"a\":[true]}"),
              new ListingField("alpha", "\"x\""));

      // when
      JsonNode item = render.sources(List.of(SOURCE), adapters).get(0);

      // then
      assertThat(keys(item)).endsWith("zeta", "alpha");
      assertThat(JSON.writeValueAsString(item.get("zeta"))).isEqualTo("{\"b\":1,\"a\":[true]}");
    }
  }

  @Nested
  @DisplayName("어댑터가 JSON이 아닌 목록 필드를 내면")
  class NotJson {
    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"{not json", ""})
    @DisplayName("목록 조회를 거부하고, 메시지에 필드 이름을 싣는다")
    void rejectsListing(String valueJson) {
      // given
      SourceAdapters adapters = listing(new ListingField("brokenField", valueJson));

      // when
      IllegalStateException failure =
          catchThrowableOfType(
              IllegalStateException.class, () -> render.sources(List.of(SOURCE), adapters));

      // then
      assertThat(failure).hasMessageContaining("brokenField");
    }
  }
}
