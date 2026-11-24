package io.scos.boardarchive.profile;

import java.time.ZoneId;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record SiteProfile(
    String site,
    String format,
    ZoneId zone,
    List<BoardProfile> boards,
    PostIdRule postId,
    Selectors selectors) {
  public SiteProfile {
    if (site.isBlank() || site.contains(":") || !Set.of("html", "json").contains(format))
      throw new IllegalArgumentException("Invalid site or format");
    boards = List.copyOf(boards);
    if (boards.stream().map(BoardProfile::id).distinct().count() != boards.size())
      throw new IllegalArgumentException("Duplicate board id");
  }

  public static SiteProfile parse(String json) {
    JsonNode root = JsonMapper.builder().build().readTree(json);
    JsonNode ids = root.path("postId"), s = root.path("selectors");
    List<BoardProfile> boards = new ArrayList<>();
    for (JsonNode b : root.path("boards"))
      boards.add(
          new BoardProfile(
              text(b, "id"),
              text(b, "name"),
              b.path("category").asString(""),
              text(b, "listUrl"),
              text(b, "postUrl"),
              strings(b.path("datePatterns"))));
    PostIdRule rule =
        new PostIdRule(
            text(ids, "urlPattern"),
            ids.path("boardPrefix").asBoolean(false),
            strings(ids.path("dropQueryParams")));
    Selectors selectors =
        new Selectors(
            text(s, "listItem"),
            text(s, "link"),
            text(s, "title"),
            text(s, "date"),
            optional(s, "pinned"),
            optional(s, "nextPage"),
            text(s, "body"),
            optional(s, "attachments"),
            optional(s, "author"),
            strings(s.path("identitySelectors")),
            strings(s.path("identityFields")));
    return new SiteProfile(
        text(root, "site"),
        text(root, "format"),
        ZoneId.of(text(root, "zone")),
        boards,
        rule,
        selectors);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank())
      throw new IllegalArgumentException("Missing profile field: " + field);
    return value.asString();
  }

  private static Optional<String> optional(JsonNode node, String field) {
    return Optional.of(node.path(field).asString("")).filter(v -> !v.isBlank());
  }

  private static List<String> strings(JsonNode node) {
    List<String> values = new ArrayList<>();
    for (JsonNode v : node) {
      if (!v.isString()) throw new IllegalArgumentException("Expected string list");
      values.add(v.asString());
    }
    return List.copyOf(values);
  }
}
