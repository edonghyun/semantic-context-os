package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroAccessScenario.FOLDERS;
import static io.scos.connector.tiro.internal.TiroAccessScenario.LIST;
import static io.scos.connector.tiro.internal.TiroAccessScenario.folder;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.tiro.internal.TiroAccessScenario.Result;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tiro 접근 단계: 전원 공개 노트 집합(유효 공개 폴더 노트 − 비공개 폴더 노트)에 든 노트만 조직 도메인 키를 얻고, 나머지는 협업자만 본다(Tiro 접근 설계
 * §5). 폴더 쪽 호출이 하나라도 실패하면 ACL을 하나도 내지 않고 실패한다(fail-closed, §5.5).
 */
@DisplayName("Tiro 접근은 전원 공개 폴더 노트에만 도메인 키를 준다(§5)")
class TiroAccessCollectionTest {
  static final String OWNER = "user:tiro-synthetic:owner@example.com";
  static final String ORG = "domain:tiro-synthetic:example.com";
  static final String PUBLIC = "ALL_MEMBER_VIEWER";

  final TiroAccessScenario tiro = new TiroAccessScenario();

  @AfterEach
  void tearDown() {
    tiro.close();
  }

  /** 노트 n1..nN의 guid. */
  static List<String> notes(int count) {
    List<String> out = new ArrayList<>();
    for (int i = 1; i <= count; i++) out.add("n" + i);
    return out;
  }

  @Nested
  @DisplayName("노트 하나가 한 폴더에만 들어 있으면")
  class SingleFolder {
    @Test
    @DisplayName("전원 공개 폴더 노트는 협업자와 조직 도메인 키다")
    void publicFolderNoteGetsDomainKey() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC));
      tiro.givenFolderNotes("10", "n1");
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.keys("note:n1")).containsExactly(ORG, OWNER);
    }

    @Test
    @DisplayName("PRIVATE 폴더 노트는 협업자 키만이다")
    void privateFolderNoteGetsCollaboratorOnly() {
      // given
      tiro.givenFolders(folder("20", null, "PRIVATE"));
      tiro.givenFolderNotes("20", "n2");
      tiro.givenStored("n2", "owner@example.com");

      // when
      Result result = tiro.collect("note:n2");

      // then
      assertThat(result.keys("note:n2")).containsExactly(OWNER);
    }

    @Test
    @DisplayName("LIMITED 폴더 노트는 협업자 키만이다")
    void limitedFolderNoteGetsCollaboratorOnly() {
      // given
      tiro.givenFolders(folder("30", null, "LIMITED"));
      tiro.givenFolderNotes("30", "n3");
      tiro.givenStored("n3", "owner@example.com");

      // when
      Result result = tiro.collect("note:n3");

      // then
      assertThat(result.keys("note:n3")).containsExactly(OWNER);
    }

    @Test
    @DisplayName("어느 폴더에도 없는 노트는 협업자 키만이다")
    void noteOutsideFoldersGetsCollaboratorOnly() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC));
      tiro.givenFolderNotes("10", "n1");
      tiro.givenStored("n9", "owner@example.com");

      // when
      Result result = tiro.collect("note:n9");

      // then
      assertThat(result.keys("note:n9")).containsExactly(OWNER);
    }
  }

  @Nested
  @DisplayName("폴더가 중첩되거나 노트가 여러 폴더에 들면")
  class NestedFolders {
    @Test
    @DisplayName("공개 부모 아래 비공개 자식의 노트는 도메인 키가 없다")
    void privateChildUnderPublicParentGetsNoDomainKey() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC), folder("11", "10", "PRIVATE"));
      tiro.givenFolderNotes("10", "n1", "n3");
      tiro.givenFolderNotes("11", "n3");
      tiro.givenStored("n1", "owner@example.com");
      tiro.givenStored("n3", "owner@example.com");

      // when
      Result result = tiro.collect("note:n1", "note:n3");

      // then
      assertThat(result.keys("note:n3")).containsExactly(OWNER);
      assertThat(result.keys("note:n1")).containsExactly(ORG, OWNER);
    }

    @Test
    @DisplayName("비공개 부모 아래 ALL_MEMBER 자식의 노트는 도메인 키가 없다")
    void publicChildUnderPrivateParentGetsNoDomainKey() {
      // given
      tiro.givenFolders(folder("20", null, "PRIVATE"), folder("21", "20", PUBLIC));
      tiro.givenFolderNotes("20", "n5");
      tiro.givenFolderNotes("21", "n5");
      tiro.givenStored("n5", "owner@example.com");

      // when
      Result result = tiro.collect("note:n5");

      // then
      assertThat(result.keys("note:n5")).containsExactly(OWNER);
    }

    @Test
    @DisplayName("공개 폴더와 비공개 형제 폴더에 함께 든 노트는 도메인 키가 없다")
    void noteInPublicAndPrivateFolderGetsNoDomainKey() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC), folder("30", null, "PRIVATE"));
      tiro.givenFolderNotes("10", "n4");
      tiro.givenFolderNotes("30", "n4");
      tiro.givenStored("n4", "owner@example.com");

      // when
      Result result = tiro.collect("note:n4");

      // then
      assertThat(result.keys("note:n4")).containsExactly(OWNER);
    }

    @Test
    @DisplayName("공개 부모·자식 두 폴더에 나온 노트는 정상이고 도메인 키다")
    void recursiveDuplicatesAreNotViolations() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC), folder("11", "10", PUBLIC));
      tiro.givenFolderNotes("10", "n1");
      tiro.givenFolderNotes("11", "n1");
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.failure()).isEmpty();
      assertThat(result.keys("note:n1")).containsExactly(ORG, OWNER);
    }
  }

  @Test
  @DisplayName("폴더별 목록은 폴더 guid가 아니라 id로 부른다")
  void listsFoldersById() {
    // given
    tiro.givenFolderListing(
        "[{\"id\":620940,\"guid\":\"7cb54dde\",\"parentId\":null,\"sharingType\":\"PRIVATE\"}]");

    // when
    tiro.collect();

    // then
    assertThat(tiro.requests())
        .contains(LIST + "?size=100&folderId=620940")
        .noneMatch(request -> request.contains("7cb54dde"));
  }

  @Test
  @DisplayName("공개 폴더 노트가 두 쪽이면 마지막 쪽 노트도 도메인 키다")
  void walksEveryFolderPage() {
    // given
    tiro.givenFolders(folder("10", null, PUBLIC));
    tiro.givenFolderNotes("10", notes(150));
    tiro.givenStored("n150", "owner@example.com");

    // when
    Result result = tiro.collect("note:n150");

    // then
    assertThat(result.keys("note:n150")).containsExactly(ORG, OWNER);
  }

  @Nested
  @DisplayName("폴더 쪽 호출이 실패하면(fail-closed)")
  class FolderFailure {
    @Test
    @DisplayName("폴더 목록이 계속 500이면 예외이고 ACL을 내지 않는다")
    void folderListingFailureIsIncompleteWithoutAcls() {
      // given
      tiro.failNext(FOLDERS, 20, FakeTiroServer.Fault.status(500));
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.failure()).containsInstanceOf(CallFailureException.class);
      assertThat(result.acls()).isEmpty();
    }

    @Test
    @DisplayName("폴더 목록이 실패해도 비용 보고는 한 번 한다")
    void folderListingFailureStillReportsCost() {
      // given
      tiro.failNext(FOLDERS, 20, FakeTiroServer.Fault.status(500));

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.costs()).hasSize(1);
    }

    @Test
    @DisplayName("공개 폴더 둘째 쪽이 403이면 예외이고 ACL을 내지 않는다")
    void folderPageFailureIsIncompleteWithoutAcls() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC));
      tiro.givenFolderNotes("10", notes(150));
      tiro.failOnCursor(LIST, "page:100", 1, FakeTiroServer.Fault.status(403));
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.failure()).containsInstanceOf(CallFailureException.class);
      assertThat(result.acls()).isEmpty();
    }

    @Test
    @DisplayName("폴더 목록이 배열이 아니면 예외이고 ACL을 내지 않는다")
    void malformedFolderListingIsIncomplete() {
      // given
      tiro.givenFolderListing("{}");
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.failure()).containsInstanceOf(TiroProtocolException.class);
      assertThat(result.acls()).isEmpty();
    }

    @Test
    @DisplayName("폴더 목록 직후 취소되면 CANCELLED가 전파되고 ACL을 내지 않는다")
    void cancellationDuringFolderWalkPropagates() {
      // given
      tiro.givenFolders(folder("10", null, PUBLIC));
      tiro.givenFolderNotes("10", "n1");
      tiro.givenStored("n1", "owner@example.com");
      tiro.cancelOnRequest("/folders");

      // when
      Result result = tiro.collect("note:n1");

      // then
      assertThat(result.failure())
          .get()
          .isInstanceOfSatisfying(
              CallFailureException.class,
              e -> assertThat(e.failure().kind()).isEqualTo(CallFailure.Kind.CANCELLED));
      assertThat(result.acls()).isEmpty();
    }
  }

  @Nested
  @DisplayName("대상이 없으면")
  class NoTargets {
    @Test
    @DisplayName("폴더 목록을 부르고 ACL 없이 정상 반환한다")
    void emptyTargetsStillReadFolders() {
      // when
      Result result = tiro.collect();

      // then
      assertThat(result.failure()).isEmpty();
      assertThat(tiro.requests()).containsExactly(FOLDERS);
    }

    @Test
    @DisplayName("폴더 목록이 실패하면 예외다")
    void emptyTargetsFolderFailureIsIncomplete() {
      // given
      tiro.failNext(FOLDERS, 20, FakeTiroServer.Fault.status(500));

      // when
      Result result = tiro.collect();

      // then
      assertThat(result.failure()).containsInstanceOf(CallFailureException.class);
    }
  }

  @Nested
  @DisplayName("대상 하나를 읽을 수 없으면")
  class TargetFailure {
    @Test
    @DisplayName("원본이 없는 대상만 DOCUMENT_MISSING이고 정상 반환한다")
    void missingStoredContentFailsOnlyThatTarget() {
      // given
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n5", "note:n1");

      // then
      assertThat(result.acl("note:n5"))
          .isEqualTo(RawAcl.failed(AccessFailureReason.DOCUMENT_MISSING, TiroAccessScenario.NOW));
      assertThat(result.keys("note:n1")).containsExactly(OWNER);
      assertThat(result.failure()).isEmpty();
    }

    @Test
    @DisplayName("원본이 JSON이 아닌 대상만 ACL_UNREADABLE이고 정상 반환한다")
    void malformedStoredContentFailsOnlyThatTarget() {
      // given
      tiro.givenStoredContent("n5", "not json");
      tiro.givenStored("n1", "owner@example.com");

      // when
      Result result = tiro.collect("note:n5", "note:n1");

      // then
      assertThat(result.acl("note:n5"))
          .isEqualTo(RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, TiroAccessScenario.NOW));
      assertThat(result.keys("note:n1")).containsExactly(OWNER);
      assertThat(result.failure()).isEmpty();
    }

    @Test
    @DisplayName("note:<guid> 형식이 아닌 대상은 INVALID_CARRIER다")
    void invalidTargetIdFails() {
      // when
      Result result = tiro.collect("folder:1");

      // then
      assertThat(result.acl("folder:1"))
          .isEqualTo(RawAcl.failed(AccessFailureReason.INVALID_CARRIER, TiroAccessScenario.NOW));
    }
  }

  @Test
  @DisplayName("협업자 신원을 관측 시각과 함께 낸다")
  void identitiesAreReported() {
    // given
    tiro.givenStored("n1", "b@example.com");

    // when
    Result result = tiro.collect("note:n1");

    // then
    assertThat(result.identities())
        .containsExactly(
            new SourceIdentityRecord(
                "b@example.com",
                Optional.of("b@example.com"),
                true,
                false,
                false,
                TiroAccessScenario.NOW));
  }

  @Test
  @DisplayName("비용은 폴더 목록·폴더 쪽·재시도 시도를 모두 센다")
  void costCountsEveryAttempt() {
    // given
    tiro.givenFolders(folder("10", null, PUBLIC), folder("20", null, "PRIVATE"));
    tiro.givenFolderNotes("10", notes(150));
    tiro.givenFolderNotes("20", "n1");
    tiro.failNext(FOLDERS, 1, FakeTiroServer.Fault.rateLimited("0"));
    tiro.givenStored("n1", "owner@example.com");
    tiro.givenStored("n2", "owner@example.com");

    // when
    Result result = tiro.collect("note:n1", "note:n2");

    // then
    assertThat(result.costs()).containsExactly(new AccessCost(2, OptionalLong.of(5), 0));
  }

  @Test
  @DisplayName("끝 로그는 대상·폴더·공개 노트·호출 수만 싣고 이메일은 싣지 않는다")
  void finishedEventCarriesCountsOnly() {
    // given
    tiro.givenFolders(folder("10", null, PUBLIC), folder("20", null, "PRIVATE"));
    tiro.givenFolderNotes("10", "n1");
    tiro.givenFolderNotes("20", "n2");
    tiro.givenStored("n1", "owner@example.com");
    tiro.givenStored("n2", "owner@example.com");

    try (LogCapture logs = LogCapture.of(TiroAccessCollection.class)) {
      // when
      tiro.collect("note:n1", "note:n2");

      // then
      assertThat(logs.events("collection.tiro_access.finished"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").toString())
                      .isEqualTo(
                          "{\"targetCount\":2,\"folderCount\":2,\"publicNotes\":1,\"calls\":3,\"elapsedMs\":0}"));
    }
  }

  @Test
  @DisplayName("어댑터 접근 정책은 수집기를 달고 노트 운반 종류를 지킨다")
  void adapterPolicyKeepsTiroCarriers() {
    // given
    tiro.givenStored("n1", "owner@example.com");

    // when
    Result result = tiro.collect("note:n1");

    // then
    assertThat(result.acl("note:n1").status()).isEqualTo(DocumentAcl.Status.OK);
    assertThat(tiro.accessPolicy().refreshKinds()).isEqualTo(Set.of("tiro.note"));
  }
}
