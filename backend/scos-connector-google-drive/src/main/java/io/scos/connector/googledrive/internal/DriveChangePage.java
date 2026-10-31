package io.scos.connector.googledrive.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 변경 피드 응답 한 페이지: 변경마다 할 작업과 다음 토큰.
 *
 * <ul>
 *   <li>드라이브 변경은 removed일 때만 그 드라이브를 지운다 — 새로 접근할 수 있게 된 드라이브는 드라이브 목록이 찾는다.
 *   <li>파일 변경은 휴지통이거나 파일 없는 removed면 지우고, 그 밖은 다시 수집한다 — corpus에서 빠졌어도 파일이 실려 오면 아직 접근할 수 있다.
 *   <li>다음 토큰은 nextPageToken(있으면 이 피드가 이어진다), 없으면 newStartPageToken이다. 비었으면 프로토콜 위반이다. 이어지는 토큰이 이미
 *       요청한 토큰으로 돌아오는지(A → A·A → B → A)는 피드 걷기 전체를 아는 {@link GoogleDriveCollection}이 본다.
 * </ul>
 *
 * @param continues nextPageToken 키가 있었는가(값이 null이어도)
 */
record DriveChangePage(List<DriveTask> tasks, String continuation, boolean continues) {
  private static final List<String> CHANGE_TYPES = List.of("file", "drive");

  static DriveChangePage read(ObjectNode page) {
    JsonNode changes = page.get("changes");
    if (changes == null || !changes.isArray())
      throw new GoogleProtocolException("Invalid Google Drive changes");
    List<DriveTask> tasks = new ArrayList<>();
    for (JsonNode entry : changes) taskOf(JsValues.object(entry)).ifPresent(tasks::add);
    JsonNode nextPageToken = page.get("nextPageToken");
    JsonNode continuation =
        nextPageToken != null && !nextPageToken.isNull()
            ? nextPageToken
            : page.get("newStartPageToken");
    if (!JsValues.nonEmptyString(continuation))
      throw new GoogleProtocolException("Google Drive change token did not advance");
    return new DriveChangePage(tasks, continuation.asString(), nextPageToken != null);
  }

  private static Optional<DriveTask> taskOf(JsonNode change) {
    JsonNode type = change.get("changeType");
    boolean driveChange = JsValues.is(type, "drive");
    JsonNode id = driveChange ? change.get("driveId") : change.get("fileId");
    if (!CHANGE_TYPES.contains(JsValues.string(type)) || !JsValues.nonEmptyString(id))
      throw new GoogleProtocolException("Invalid Google Drive change identity");
    boolean removed = JsValues.isTrue(change.get("removed"));
    if (driveChange)
      return removed ? Optional.of(DriveTask.removeDrive(id.asString())) : Optional.empty();
    JsonNode file = change.has("file") ? JsValues.object(change.get("file")) : null;
    if ((file != null && JsValues.isTrue(file.get("trashed"))) || (removed && file == null))
      return Optional.of(DriveTask.remove(id.asString()));
    return Optional.of(DriveTask.file(id.asString()));
  }
}
