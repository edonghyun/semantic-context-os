package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.googledrive.GoogleDriveProjector;
import io.scos.engine.internal.projection.ProjectorFixture.DocView;
import io.scos.engine.internal.projection.ProjectorFixture.UnitView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * F7 동등성(TESTING §3.6 4층): harness/fixtures/google-drive 첫 수집의 TS 원본(expected-raw-items.json) →
 * GoogleDriveProjector → expected-documents.json(TS GoogleDriveProjector)과 같고, 파이프라인(800토큰 분할)을 거친
 * 유닛은 expected-units.json과 같다. 표본: Docs(탭·하위 탭·머리말·표·리치 링크), 탭 없는 Docs, 시트 메타데이터와 범위(셀 `A1:
 * 값`·수식·메모·링크, 버전이 바뀐 범위의 수집 제한), Slides, 텍스트·JSON, 댓글·답글, 수집 제한, 바로가기·폴더·공유 드라이브. 64 KiB를 넘어
 * blob으로 저장된 Docs·시트 범위 원본도 투영기가 그 바이트를 읽는다.
 */
@DisplayName("Drive 투영기는 TS 원본에서 TS와 같은 문서·유닛을 낸다(F7, §3.6)")
class GoogleDriveProjectorFixtureTest {
  static final Path FIXTURES =
      Path.of(
          System.getProperty("scos.fixtures.google-drive.dir", "../harness/fixtures/google-drive"));

  /** inline 상한(64 KiB)을 넘어 blob으로 저장된 JSON 원본: Docs 하나·시트 범위 하나. */
  static final long BLOB_ORIGINALS = 2;

  final ProjectorFixture fixture =
      ProjectorFixture.of(
          FIXTURES, new GoogleDriveProjector(new ObjectMapper()), "google-drive-synthetic");

  @Test
  @DisplayName("파일·드라이브 원본마다 문서 하나로 묶고 시트 범위는 그 파일에 붙인다")
  void groupsLikeTs() {
    // when
    List<String> documentIds = fixture.documentIds();

    // then
    assertThat(documentIds).containsExactlyElementsOf(fixture.expectedDocumentIds());
  }

  @Test
  @DisplayName("투영한 문서가 TS와 id·uri·제목·본문·시각까지 같다")
  void documentsMatchTs() {
    // given
    List<DocView> expected = fixture.expectedDocuments();
    if (expected.isEmpty())
      throw new IllegalStateException("given 준비 실패: 기대 문서(expected-documents.json)가 있어야 한다");

    // when
    List<DocView> documents = fixture.documents();

    // then
    assertThat(documents).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("투영한 문서를 파이프라인에 넣은 유닛이 TS 기대와 같다")
  void unitsMatchTs() {
    // given
    List<UnitView> expected = fixture.expectedUnits();
    if (expected.isEmpty())
      throw new IllegalStateException("given 준비 실패: 기대 유닛 사례(expected-units.json)가 있어야 한다");

    // when
    List<UnitView> units = fixture.units();

    // then
    assertThat(units).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("fixture에는 blob으로 저장된 JSON 원본(파일·시트 범위)이 있다")
  void fixtureHasBlobOriginals() throws Exception {
    // when
    long blobOriginals = jsonBlobOriginals();

    // then
    assertThat(blobOriginals).isEqualTo(BLOB_ORIGINALS);
  }

  /** 기대 원본 가운데 바이너리가 아닌 blob(64 KiB 넘는 JSON 원본) 수. */
  static long jsonBlobOriginals() throws Exception {
    JsonNode raw =
        new ObjectMapper().readTree(Files.readString(FIXTURES.resolve("expected-raw-items.json")));
    return raw.get("items")
        .valueStream()
        .filter(i -> !i.get("resourceType").asString().equals("gdrive.binary"))
        .filter(i -> i.get("content").get("storage").asString().equals("blob"))
        .count();
  }
}
