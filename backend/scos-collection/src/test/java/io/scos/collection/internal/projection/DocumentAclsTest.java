package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.SourceInstances;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("문서 ACL은 권한 운반 원본의 교집합으로 닫힌다(접근 설계 §3.2)")
class DocumentAclsTest {
  private static final Instant OBSERVED_AT = Instant.parse("2026-10-14T00:00:00Z");
  private static final Instant DECIDED_AT = Instant.parse("2026-11-02T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
  private static final String LOCAL_URL = "http://127.0.0.1:1";
  private static final AccessKey U1 = AccessKey.parse("user:slack-a:U1");
  private static final AccessKey U2 = AccessKey.parse("user:slack-a:U2");
  private static final AccessKey U3 = AccessKey.parse("user:slack-a:U3");
  private final SourceDescriptor slack = source("slack-a", "slack");
  private final SourceAdapter slackAdapter = SlackAssembly.adapter(LOCAL_URL, "synthetic", CLOCK);
  private final SourceDescriptor notion = source("notion-a", "notion");
  private final SourceAdapter notionAdapter = NotionAssembly.adapter(LOCAL_URL, "synthetic", CLOCK);
  private final DocumentAcls acls =
      new DocumentAcls(new AccessCollection.Options(Duration.ofDays(30)));

  @Test
  @DisplayName("정상 carrier의 키는 합집합이 아닌 교집합이다")
  void okCarriersIntersect() {
    // given
    var items =
        List.of(
            message("m1", observed(Status.OK, U1, U2)), message("m2", observed(Status.OK, U2, U3)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl).isEqualTo(new DocumentAcl("doc-1", Status.OK, Set.of(U2), DECIDED_AT));
  }

  @Test
  @DisplayName("실패 carrier가 하나라도 있으면 문서는 FAILED로 닫힌다")
  void failedCarrierFailsDocument() {
    // given
    var items =
        List.of(message("m1", observed(Status.OK, U1)), message("m2", observed(Status.FAILED, U1)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1",
                Status.FAILED,
                Set.of(),
                DECIDED_AT,
                AccessFailureReason.PROVIDER_FAILURE));
  }

  @Test
  @DisplayName("조각만 있고 carrier가 없으면 문서는 FAILED로 닫힌다")
  void missingCarrierFailsDocument() {
    // given
    var fragment =
        RawItems.item("block-1").resourceType("notion.block").acl(observed(Status.OK, U1)).build();
    // when
    var acl = calculate(slack, slackAdapter, List.of(fragment));
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.NO_CARRIERS));
  }

  @Test
  @DisplayName("정상과 미지원 carrier가 섞이면 문서는 FAILED로 닫힌다")
  void mixedUnsupportedAndOkFails() {
    // given
    var items =
        List.of(
            message("m1", observed(Status.OK, U1)), message("m2", observed(Status.UNSUPPORTED)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("Notion은 원본 없이 소스 인스턴스의 미지원 scope를 받는다")
  void unsupportedSourceUsesInstanceScope() {
    // when
    var acl = calculate(notion, notionAdapter, List.of());
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1",
                Status.UNSUPPORTED,
                Set.of(AccessKey.parse("scope:notion-a:*")),
                DECIDED_AT));
  }

  @Test
  @DisplayName("시트 조각의 UNKNOWN은 파일 carrier의 권한을 막지 않는다")
  void fragmentUnknownDoesNotBlockFile() {
    // given
    var drive = source("drive-a", "google-drive");
    var adapter =
        GoogleDriveAssembly.adapter(LOCAL_URL, GoogleDriveAssembly.CREDENTIAL_JSON, CLOCK);
    var key = AccessKey.parse("user:drive-a:reader");
    var file =
        RawItems.item("file:F1").resourceType("gdrive.file").acl(observed(Status.OK, key)).build();
    var sheet =
        RawItems.item("sheet:S1")
            .resourceType("gdrive.sheet")
            .acl(observed(Status.UNKNOWN))
            .build();
    // when
    var acl = calculate(drive, adapter, List.of(sheet, file));
    // then
    assertThat(acl).isEqualTo(new DocumentAcl("doc-1", Status.OK, Set.of(key), DECIDED_AT));
  }

  @Test
  @DisplayName("서로 겹치지 않는 carrier는 OK와 빈 키로 아무에게도 허용하지 않는다")
  void emptyIntersectionGrantsNobody() {
    // given
    var items =
        List.of(message("m1", observed(Status.OK, U1)), message("m2", observed(Status.OK, U2)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl).isEqualTo(new DocumentAcl("doc-1", Status.OK, Set.of(), DECIDED_AT));
  }

  @Test
  @DisplayName("다른 인스턴스의 키를 가진 carrier는 FAILED로 닫힌다")
  void foreignInstanceKeyFailsDocument() {
    // given
    var foreign = AccessKey.parse("group:slack-b:C1");
    // when
    var acl = calculate(slack, slackAdapter, List.of(message("m1", observed(Status.OK, foreign))));
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("지원 원천의 정상 carrier에 scope가 있으면 FAILED로 닫힌다")
  void scopeOnSupportedSourceFailsDocument() {
    // given
    var scope = AccessKey.parse("scope:slack-a:*");
    // when
    var acl = calculate(slack, slackAdapter, List.of(message("m1", observed(Status.OK, scope))));
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("지원 원천의 carrier가 모두 UNSUPPORTED여도 FAILED로 닫힌다")
  void unsupportedStatusOnSupportedSourceFailsDocument() {
    // given
    var carrier = message("m1", observed(Status.UNSUPPORTED));
    // when
    var acl = calculate(slack, slackAdapter, List.of(carrier));
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("UNKNOWN carrier가 하나라도 있으면 FAILED로 닫힌다")
  void unknownCarrierFailsDocument() {
    // given
    var items =
        List.of(
            message("m1", observed(Status.OK, U1)), message("m2", observed(Status.UNKNOWN, U1)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.INVALID_CARRIER));
  }

  @Test
  @DisplayName("비관측 carrier가 하나라도 있으면 FAILED로 닫힌다")
  void unobservedCarrierFailsDocument() {
    // given
    var items =
        List.of(message("m1", observed(Status.OK, U1)), message("m2", RawAcl.notObserved()));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1",
                Status.FAILED,
                Set.of(),
                DECIDED_AT,
                AccessFailureReason.UNOBSERVED_CARRIER));
  }

  @Test
  @DisplayName("원본이 비어 있는 지원 문서는 FAILED로 닫힌다")
  void emptyItemsFailDocument() {
    // when
    var acl = calculate(slack, slackAdapter, List.of());
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.NO_CARRIERS));
  }

  @Test
  @DisplayName("모든 carrier가 교집합을 제한한다")
  void allCarriersConstrainDocument() {
    // given
    var items =
        List.of(
            message("m1", observed(Status.OK, U1, U2)),
            message("m2", observed(Status.OK, U2, U3)),
            message("m3", observed(Status.OK, U1, U3)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl).isEqualTo(new DocumentAcl("doc-1", Status.OK, Set.of(), DECIDED_AT));
  }

  @Test
  @DisplayName("교집합에서 사라질 외부 키도 검증해 FAILED로 닫힌다")
  void discardedForeignKeyStillFailsDocument() {
    // given
    var foreign = AccessKey.parse("user:slack-b:U1");
    var items =
        List.of(message("m1", observed(Status.OK)), message("m2", observed(Status.OK, foreign)));
    // when
    var acl = calculate(slack, slackAdapter, items);
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1", Status.FAILED, Set.of(), DECIDED_AT, AccessFailureReason.INVALID_CARRIER));
  }

  @ParameterizedTest
  @ValueSource(strings = {"user:slack-a:U1", "group:slack-a:C1", "domain:slack-a:example.com"})
  @DisplayName("단일 정상 carrier의 유효한 접근 키는 보존한다")
  void singleCarrierKeepsKeys(String text) {
    // given
    var key = AccessKey.parse(text);
    // when
    var acl = calculate(slack, slackAdapter, List.of(message("m1", observed(Status.OK, key))));
    // then
    assertThat(acl).isEqualTo(new DocumentAcl("doc-1", Status.OK, Set.of(key), DECIDED_AT));
  }

  @Test
  @DisplayName("Notion은 원본 ACL의 실패와 외부 키를 계산에 사용하지 않는다")
  void unsupportedSourceIgnoresRawAcl() {
    // given
    var item =
        RawItems.item("page:P1")
            .resourceType("notion.page")
            .acl(observed(Status.FAILED, U1))
            .build();
    // when
    var acl = calculate(notion, notionAdapter, List.of(item));
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1",
                Status.UNSUPPORTED,
                Set.of(AccessKey.parse("scope:notion-a:*")),
                DECIDED_AT));
  }

  @Test
  @DisplayName("미지원 선언이 다른 원천에 연결돼도 Notion 외 scope를 만들지 않는다")
  void unsupportedNonNotionSourceFailsDocument() {
    // given
    var tiro = source("tiro-a", "tiro");
    // when
    var acl = calculate(tiro, notionAdapter, List.of());
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1",
                Status.FAILED,
                Set.of(),
                DECIDED_AT,
                AccessFailureReason.UNSUPPORTED_SOURCE));
  }

  @ParameterizedTest
  @CsvSource({
    "slack,bad:instance",
    "slack,bad%instance",
    "notion,bad:instance",
    "notion,bad%instance"
  })
  @DisplayName("비정규 인스턴스는 미지원 원천도 예외 없이 FAILED로 닫힌다")
  void nonCanonicalInstanceFailsDocument(String kind, String instance) {
    // given
    var source = source(instance, kind);
    var adapter = kind.equals("notion") ? notionAdapter : slackAdapter;
    var carrier = message("m1", observed(Status.OK));
    // when
    var acl = calculate(source, adapter, List.of(carrier));
    // then
    assertThat(acl)
        .isEqualTo(
            new DocumentAcl(
                "doc-1",
                Status.FAILED,
                Set.of(),
                DECIDED_AT,
                AccessFailureReason.INVALID_INSTANCE));
  }

  private DocumentAcl calculate(
      SourceDescriptor source, SourceAdapter adapter, List<RawItem> items) {
    return acls.calculate(source, adapter, "doc-1", items, DECIDED_AT);
  }

  private static SourceDescriptor source(String instance, String kind) {
    return SourceInstances.of(
            new SourceInstanceId(instance), kind, "{}", "env:SYNTHETIC", OBSERVED_AT)
        .descriptor();
  }

  private static RawItem message(String id, RawAcl acl) {
    return RawItems.item(id).resourceType("slack.message").acl(acl).build();
  }

  private static RawAcl observed(Status status, AccessKey... keys) {
    return RawAcl.observed(status, Set.of(keys), OBSERVED_AT);
  }

  @Test
  @DisplayName("24시간 상한에 도달한 원본의 권한은 투영에서도 FAILED다")
  void staleCarrierFailsDocument() {
    var acl =
        new DocumentAcls()
            .calculate(
                source("drive-a", "google-drive"),
                GoogleDriveAssembly.adapter(LOCAL_URL, GoogleDriveAssembly.CREDENTIAL_JSON, CLOCK),
                "doc-1",
                List.of(
                    RawItems.item("file:F1")
                        .resourceType("gdrive.file")
                        .acl(RawAcl.observed(Status.OK, Set.of(), OBSERVED_AT))
                        .build()),
                OBSERVED_AT.plusSeconds(86400));
    // then
    assertThat(acl.status()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("24시간 상한 직전 원본은 정상 권한을 유지한다")
  void carrierBeforeExpiryKeepsGrant() {
    var acl =
        new DocumentAcls()
            .calculate(
                source("drive-a", "google-drive"),
                GoogleDriveAssembly.adapter(LOCAL_URL, GoogleDriveAssembly.CREDENTIAL_JSON, CLOCK),
                "doc-1",
                List.of(
                    RawItems.item("file:F1")
                        .resourceType("gdrive.file")
                        .acl(RawAcl.observed(Status.OK, Set.of(), OBSERVED_AT))
                        .build()),
                OBSERVED_AT.plusSeconds(86399));
    // then
    assertThat(acl.status()).isEqualTo(Status.OK);
  }
}
