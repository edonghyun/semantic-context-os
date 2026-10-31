package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.notion.internal.NotionScenario.Collection;
import io.scos.connector.notion.internal.NotionScenario.Config;
import io.scos.spi.connector.Checkpoint;
import io.scos.support.FakeHttpServer.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 목록 걷기의 커서 자취가 걷기마다 따로인가 — 커서 순환 위반이 다른 걷기의 자취와 섞여 오탐이 나는 경로가 있는지 본다. 블록 자식의 next_cursor는 다음 페이지 첫
 * 블록의 id다(가짜도 블록 id 모양의 커서를 쓴다).
 */
@DisplayName("Notion 목록 걷기의 커서 자취는 걷기마다 따로다")
class NotionReadsTest {
  static final String ROOT_CHILDREN = "/v1/blocks/root-wiki/children";
  static final String DUP_CHILDREN = "/v1/blocks/blk-dup/children";

  final NotionScenario notion = new NotionScenario();

  @AfterEach
  void close() {
    notion.close();
  }

  /** 자식이 있는 toggle 블록(blk-dup)이 루트 본문에 두 번 나온다 — 한 run이 그 블록의 자식을 두 번 걷는다. 그 자식은 두 페이지다. */
  void givenBlockListedTwice() {
    notion.script(
        "GET",
        ROOT_CHILDREN,
        cursor -> page(List.of(block("blk-dup", true), block("blk-dup", true)), Optional.empty()));
    notion.script(
        "GET",
        DUP_CHILDREN,
        cursor ->
            cursor.isEmpty()
                ? page(List.of(block("blk-dup-1", false)), Optional.of("blk-dup-2"))
                : page(List.of(block("blk-dup-2", false)), Optional.empty()));
  }

  @Test
  @DisplayName("같은 블록을 한 run에서 두 번 걸어도 앞 걷기의 커서로 위반이 나지 않는다")
  void sameBlockWalkedTwiceDoesNotTrip() {
    // given
    givenBlockListedTwice();

    // when
    Collection java = notion.collect(Config.ROOTS);

    // then
    assertThat(java.failure()).isEmpty();
    assertThat(notion.requests().stream().filter(r -> r.path().equals(DUP_CHILDREN)))
        .as("두 걷기 × 두 페이지")
        .hasSize(4);
  }

  @Test
  @DisplayName("걷기 하나의 페이지는 그 사이에 다른 요청 없이 연달아 나간다(자취가 다음 커서 하나로 이어지는 전제)")
  void walkPagesAreConsecutive() {
    // given
    givenBlockListedTwice();

    // when
    notion.collect(Config.ROOTS);

    // then
    List<String> requests =
        notion.requests().stream()
            .map(
                r ->
                    r.path()
                        + Optional.ofNullable(r.params().get("start_cursor"))
                            .map(c -> "?" + c)
                            .orElse(""))
            .toList();
    int first = requests.indexOf(DUP_CHILDREN);
    int second = requests.lastIndexOf(DUP_CHILDREN);
    assertThat(second).as("두 번째 걷기의 첫 페이지").isGreaterThan(first + 1);
    assertThat(List.of(requests.subList(first, first + 2), requests.subList(second, second + 2)))
        .containsExactly(
            List.of(DUP_CHILDREN, DUP_CHILDREN + "?blk-dup-2"),
            List.of(DUP_CHILDREN, DUP_CHILDREN + "?blk-dup-2"));
  }

  @Test
  @DisplayName("체크포인트에서 재개한 걷기는 앞 run이 요청한 커서를 모른다(오탐이 아니라 놓침만 있다)")
  void resumedWalkStartsFreshTrail() {
    // given — 앞 run이 -, blk-dup-2를 요청하고 blk-dup-3 앞에서 멈췄다. 공급자는 이제 blk-dup-3 다음으로 blk-dup-2를 준다
    notion.script(
        "GET",
        DUP_CHILDREN,
        cursor ->
            cursor.equals(Optional.of("blk-dup-3"))
                ? page(List.of(block("blk-dup-3", false)), Optional.of("blk-dup-2"))
                : page(List.of(block("blk-dup-2", false)), Optional.empty()));
    Checkpoint resumed =
        new Checkpoint(
            NotionRootTraversal.SCHEMA_VERSION,
            "{\"mode\":\"roots\",\"rootPageIds\":[\"root-wiki\",\"root-retro\",\"root-archive\"],"
                + "\"stage\":\"traversal\",\"pendingWork\":[{\"kind\":\"block_children\","
                + "\"blockId\":\"blk-dup\",\"cursor\":\"blk-dup-3\"}]}");

    // when
    Collection java = notion.collectFrom(Config.ROOTS, resumed);

    // then
    assertThat(java.failure()).isEmpty();
  }

  @Disabled(
      "spec: 걷는 동안 문서 편집으로 블록이 옮겨져 이미 요청한 블록 id가 다음 커서로 다시 와도 run을 실패시키지 않는다 — 오탐인지는 사람 판단 대기(L1 보고서 §3)")
  @Test
  @DisplayName("걷는 동안 앞 페이지의 첫 블록이 뒤로 옮겨져 다음 커서로 다시 와도 run은 실패하지 않는다")
  void blockMovedDuringWalkDoesNotFailRun() {
    // given — 루트 본문 - → [a] blk-x → [x] blk-y → (편집: x를 y 페이지 끝 뒤로 옮김) [y] blk-x → [x] 끝
    AtomicInteger xServed = new AtomicInteger();
    notion.script(
        "GET",
        ROOT_CHILDREN,
        cursor ->
            switch (cursor.orElse("-")) {
              case "-" -> page(List.of(block("blk-a", false)), Optional.of("blk-x"));
              case "blk-x" ->
                  xServed.getAndIncrement() == 0
                      ? page(List.of(block("blk-x", false)), Optional.of("blk-y"))
                      : page(List.of(block("blk-x", false)), Optional.empty());
              default -> page(List.of(block("blk-y", false)), Optional.of("blk-x"));
            });

    // when
    Collection java = notion.collect(Config.ROOTS);

    // then
    assertThat(java.failureMessage()).isEmpty();
  }

  /** 블록 자식 목록 한 페이지(Notion 봉투). */
  static Response page(List<String> blocks, Optional<String> next) {
    return new Response(
        200,
        "{\"object\":\"list\",\"results\":["
            + String.join(",", blocks)
            + "],\"next_cursor\":"
            + next.map(c -> "\"" + c + "\"").orElse("null")
            + ",\"has_more\":"
            + next.isPresent()
            + ",\"type\":\"block\",\"block\":{}}",
        Map.of());
  }

  /** 루트 본문의 toggle 블록 하나(데이터셋 블록과 같은 모양). */
  static String block(String id, boolean hasChildren) {
    return "{\"object\":\"block\",\"id\":\""
        + id
        + "\",\"parent\":{\"type\":\"page_id\",\"page_id\":\"root-wiki\"},"
        + "\"created_time\":\"2026-08-01T00:10:00.000Z\",\"last_edited_time\":\"2026-08-02T00:00:00.000Z\","
        + "\"created_by\":{\"object\":\"user\",\"id\":\"user-synthetic\"},"
        + "\"last_edited_by\":{\"object\":\"user\",\"id\":\"user-synthetic\"},"
        + "\"has_children\":"
        + hasChildren
        + ",\"archived\":false,\"in_trash\":false,\"type\":\"toggle\","
        + "\"toggle\":{\"rich_text\":[],\"color\":\"default\"}}";
  }
}
