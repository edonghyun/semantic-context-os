package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.connector.notion.internal.NotionMutations.Resource;
import io.scos.connector.notion.internal.NotionRootTraversal.Advance;
import io.scos.connector.notion.internal.NotionRootTraversal.Step;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * roots 모드 run 하나(TS collectConfiguredRoots·processWork): 작업 큐의 작업 하나(또는 discovery 검색 한 페이지)가 페이지
 * 하나다. 사이클이 끝나는 페이지까지 낸다. 작업별 동작은 TS와 같다.
 *
 * <ul>
 *   <li>page: 페이지 조회 → 원본. 휴지통이 아니면 본문 블록 작업. 설정한 루트의 조회만 object_not_found를 격리하지 않는다.
 *   <li>block_children: 블록 원본(부모 = 이 블록), 다음 커서는 이어지는 작업으로 맨 앞에. 휴지통이 아닌 child_page는 페이지 작업,
 *       child_database는 database 작업, 그 밖의 자식이 있는 블록은 블록 작업.
 *   <li>database: 원본, 휴지통이 아니면 data source마다 작업. data_source: 원본, 휴지통이 아니면 행 작업.
 *   <li>data_source_rows: 행 페이지 원본과 그 본문 블록 작업, 중첩 data source 작업. 휴지통 행이 오면 Error. 휴지통 단계 작업(옛
 *       체크포인트)은 부르지 않고 넘긴다 — 사라진 행은 complete_snapshot 정리가 지운다.
 * </ul>
 */
public final class NotionRootCollection {
  static final String WRONG_PAGE = "Notion returned the wrong page";
  static final String WRONG_DATABASE = "Notion returned the wrong database";
  static final String WRONG_DATA_SOURCE = "Notion returned the wrong data source";
  static final String TRASHED_ROW = "Notion data source query returned a trashed item";

  private record Processed(
      List<RawMutation> mutations,
      Optional<NotionWork> continuation,
      List<NotionWork> discovered,
      Optional<RejectedItem> rejection) {
    static Processed rejected(RejectedItem rejection) {
      return new Processed(List.of(), Optional.empty(), List.of(), Optional.of(rejection));
    }

    static Processed skipped() {
      return new Processed(List.of(), Optional.empty(), List.of(), Optional.empty());
    }
  }

  private final Roots config;
  private final NotionRootTraversal traversal;
  private final NotionApiClient client;
  private final NotionCalls calls;
  private final NotionMutations mutations;
  private final String token;

  public NotionRootCollection(
      Roots config,
      NotionRootTraversal traversal,
      NotionApiClient client,
      NotionCalls calls,
      NotionMutations mutations,
      String token) {
    this.config = config;
    this.traversal = traversal;
    this.client = client;
    this.calls = calls;
    this.mutations = mutations;
    this.token = token;
  }

  public void collect(PageSink sink) {
    boolean cycleComplete = false;
    while (!cycleComplete) {
      Advance advance;
      List<RawMutation> changed;
      List<RejectedItem> rejections;
      switch (traversal.next()) {
        case Step.Work work -> {
          Processed processed = process(work.work());
          advance = traversal.completeWork(processed.continuation(), processed.discovered());
          changed = processed.mutations();
          rejections = processed.rejection().stream().toList();
        }
        case Step.Search search -> {
          NotionApiClient.ListPage result =
              calls.required(
                  NotionApiClient.SEARCH_OPERATION,
                  timeout -> client.searchPages(token, search.cursor(), search.phase(), timeout));
          changed = mutations.searchResults(result.results(), search.phase());
          advance =
              traversal.completeSearch(
                  result.results().stream().map(p -> p.get("id").asString()).toList(),
                  result.nextCursor());
          rejections = List.of();
        }
      }
      sink.accept(new CollectionPage(changed, rejections, advance.checkpoint()));
      cycleComplete = advance.cycleComplete();
    }
  }

  private Processed process(NotionWork work) {
    return switch (work) {
      case NotionWork.Page page -> page(page);
      case NotionWork.BlockChildren blocks -> blockChildren(blocks);
      case NotionWork.Database database -> database(database);
      case NotionWork.DataSource dataSource -> dataSource(dataSource);
      case NotionWork.DataSourceRows rows -> rows(rows);
    };
  }

  private Processed page(NotionWork.Page work) {
    String pageId = work.pageId();
    if (config.rootPageIds().contains(pageId))
      return retrievedPage(
          pageId,
          calls.required(work.operation(), timeout -> client.retrievePage(token, pageId, timeout)));
    return calls
        .isolated(work, timeout -> client.retrievePage(token, pageId, timeout))
        .fold(Processed::rejected, page -> retrievedPage(pageId, page));
  }

  private Processed retrievedPage(String pageId, JsonNode page) {
    if (!page.get("id").asString().equals(pageId)) throw new NotionProtocolException(WRONG_PAGE);
    return new Processed(
        List.of(mutations.page(page)),
        Optional.empty(),
        trashed(page) ? List.of() : List.of(new NotionWork.BlockChildren(pageId, Optional.empty())),
        Optional.empty());
  }

  private Processed blockChildren(NotionWork.BlockChildren work) {
    String blockId = work.blockId();
    return calls
        .isolated(work, timeout -> client.listBlockChildren(token, blockId, work.cursor(), timeout))
        .fold(Processed::rejected, result -> listedBlocks(blockId, result));
  }

  private Processed listedBlocks(String blockId, NotionApiClient.ListPage result) {
    List<RawMutation> changed = new ArrayList<>();
    List<NotionWork> discovered = new ArrayList<>();
    for (JsonNode block : result.results()) {
      changed.add(mutations.block(blockId, block));
      descendant(block).ifPresent(discovered::add);
    }
    return new Processed(
        changed,
        result.nextCursor().map(next -> new NotionWork.BlockChildren(blockId, Optional.of(next))),
        discovered,
        Optional.empty());
  }

  /** TS blockDescendantWork. */
  private static Optional<NotionWork> descendant(JsonNode block) {
    if (trashed(block)) return Optional.empty();
    String id = block.get("id").asString();
    return switch (block.get("type").asString()) {
      case "child_page" -> Optional.of(new NotionWork.Page(id));
      case "child_database" -> Optional.of(new NotionWork.Database(id));
      default ->
          block.get("has_children").asBoolean()
              ? Optional.of(new NotionWork.BlockChildren(id, Optional.empty()))
              : Optional.empty();
    };
  }

  private Processed database(NotionWork.Database work) {
    String databaseId = work.databaseId();
    return calls
        .isolated(work, timeout -> client.retrieveDatabase(token, databaseId, timeout))
        .fold(Processed::rejected, database -> retrievedDatabase(databaseId, database));
  }

  private Processed retrievedDatabase(String databaseId, JsonNode database) {
    if (!database.get("id").asString().equals(databaseId))
      throw new NotionProtocolException(WRONG_DATABASE);
    List<NotionWork> discovered = new ArrayList<>();
    if (!trashed(database))
      database
          .get("data_sources")
          .forEach(ds -> discovered.add(new NotionWork.DataSource(ds.get("id").asString())));
    return new Processed(
        List.of(mutations.container(Resource.DATABASE, database)),
        Optional.empty(),
        discovered,
        Optional.empty());
  }

  private Processed dataSource(NotionWork.DataSource work) {
    String dataSourceId = work.dataSourceId();
    return calls
        .isolated(work, timeout -> client.retrieveDataSource(token, dataSourceId, timeout))
        .fold(Processed::rejected, dataSource -> retrievedDataSource(dataSourceId, dataSource));
  }

  private Processed retrievedDataSource(String dataSourceId, JsonNode dataSource) {
    if (!dataSource.get("id").asString().equals(dataSourceId))
      throw new NotionProtocolException(WRONG_DATA_SOURCE);
    return new Processed(
        List.of(mutations.container(Resource.DATA_SOURCE, dataSource)),
        Optional.empty(),
        trashed(dataSource)
            ? List.of()
            : List.of(
                new NotionWork.DataSourceRows(dataSourceId, SearchPhase.ACTIVE, Optional.empty())),
        Optional.empty());
  }

  private Processed rows(NotionWork.DataSourceRows work) {
    if (work.phase() == SearchPhase.TRASH) return Processed.skipped();
    return calls
        .isolated(
            work,
            timeout -> client.queryDataSource(token, work.dataSourceId(), work.cursor(), timeout))
        .fold(Processed::rejected, result -> queriedRows(work, result));
  }

  private Processed queriedRows(NotionWork.DataSourceRows work, NotionApiClient.ListPage result) {
    for (JsonNode entry : result.results())
      if (trashed(entry)) throw new NotionProtocolException(TRASHED_ROW);
    List<RawMutation> changed = new ArrayList<>();
    List<NotionWork> rowBodies = new ArrayList<>();
    List<NotionWork> nested = new ArrayList<>();
    for (JsonNode entry : result.results()) {
      String id = entry.get("id").asString();
      if (entry.get("object").asString().equals(NotionObjects.PAGE)) {
        changed.add(mutations.page(entry));
        rowBodies.add(new NotionWork.BlockChildren(id, Optional.empty()));
      } else {
        nested.add(new NotionWork.DataSource(id));
      }
    }
    rowBodies.addAll(nested);
    return new Processed(
        changed,
        result
            .nextCursor()
            .map(
                next ->
                    new NotionWork.DataSourceRows(
                        work.dataSourceId(), work.phase(), Optional.of(next))),
        rowBodies,
        Optional.empty());
  }

  private static boolean trashed(JsonNode value) {
    return value.get("in_trash").asBoolean();
  }
}
