package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.Checkpoint;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drive 체크포인트(TS DriveProgress): 저장 원문의 키 순서가 아니라 JS 객체 순서로 돈다 — 원문이 jsonb·다른 직렬화를 거쳐도 삭제 순서가 TS와
 * 같다.
 */
@DisplayName("Drive 체크포인트는 TS DriveProgress처럼 JS 객체 순서로 돈다")
class DriveProgressTest {
  static final String ACCOUNT = "perm-reader";

  @Test
  @DisplayName("공유 드라이브의 파일은 배열 인덱스 같은 id를 먼저 센다")
  void filesInDriveFollowJsObjectOrder() {
    // given
    DriveProgress progress =
        DriveProgress.resume(
            GoogleDriveFixtures.JSON,
            Optional.of(
                new Checkpoint(
                    1,
                    "{\"accountId\":\""
                        + ACCOUNT
                        + "\",\"pending\":[],\"seenFiles\":[],\"downloadedVersions\":{},"
                        + "\"files\":{\"z\":{\"driveId\":\"t\",\"children\":[]},"
                        + "\"10\":{\"driveId\":\"t\",\"children\":[]},"
                        + "\"2\":{\"driveId\":\"t\",\"children\":[]}}}")),
            ACCOUNT);

    // when
    List<String> ids = progress.filesInDrive("t");

    // then
    assertThat(ids).containsExactly("2", "10", "z");
  }
}
