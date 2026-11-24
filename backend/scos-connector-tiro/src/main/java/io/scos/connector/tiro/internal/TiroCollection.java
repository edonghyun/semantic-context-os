package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroProtocolException.invalid;

import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * run 하나가 소유하는 Tiro 수집: 워크스페이스 노트 목록(폴더 또는 전체)을 첫 페이지부터 끝까지 걷고, 페이지마다 노트를 하나씩 읽어({@link
 * TiroNoteReader}) 그 페이지의 변경과 다음 커서를 낸다.
 */
public final class TiroCollection {
  static final int LIST_PAGE_SIZE = 20;
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]+$");

  private final TiroConfiguration config;
  private final TiroReads reads;
  private final TiroNoteReader notes;

  public TiroCollection(
      CollectionInput input,
      TiroConfiguration config,
      String token,
      TiroApiClient client,
      ObjectMapper json,
      Clock clock) {
    this.config = config;
    this.reads = new TiroReads(input.calls(), input.cancel(), client, token);
    this.notes = new TiroNoteReader(reads, input.content(), config, json, clock);
  }

  public void collect(PageSink sink) {
    String listPath = "/v1/external/workspaces/" + config.workspaceGuid() + "/notes";
    CursorWalk walk = new CursorWalk(listPath, LIST_PAGE_SIZE, "Tiro pagination did not advance");
    Optional<String> cursor = Optional.empty();
    do {
      // 취소는 실행기가 호출마다 먼저 본다
      Map<String, String> query = walk.query(cursor);
      if (config.selection() instanceof TiroConfiguration.Selection.Folder folder)
        query.put("folderId", folder.folderId());
      TiroCursorPage page =
          TiroCursorPage.read(reads.read(listPath, query, TiroReads.Scope.WORKSPACE));
      walk.received(page.nextCursor());
      List<RawMutation> mutations = new ArrayList<>();
      for (ObjectNode listed : page.entries()) mutations.add(notes.read(guid(listed)));
      cursor = page.nextCursor();
      sink.accept(
          new CollectionPage(mutations, List.of(), TiroCheckpoint.write(config.scope(), cursor)));
    } while (cursor.isPresent());
  }

  /** 목록 항목의 노트 guid(`[A-Za-z0-9_-]+`). 아니면 위반. */
  static String guid(ObjectNode listed) {
    JsonNode guid = listed.get("guid");
    if (guid == null || !guid.isString() || !ID.matcher(guid.asString()).matches())
      throw invalid("Invalid Tiro note identity");
    return guid.asString();
  }
}
