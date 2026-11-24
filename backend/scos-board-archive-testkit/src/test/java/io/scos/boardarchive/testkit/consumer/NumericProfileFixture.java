package io.scos.boardarchive.testkit.consumer;

import io.scos.boardarchive.profile.*;
import io.scos.boardarchive.testkit.LocalSite;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** Numeric ids and site-specific paths are parsed by the production JSON driver. */
final class NumericProfileFixture {
  private static final String FIRST_PAGE =
      """
        {"items":[
          {"link":"/view/114","title":"Notice","date":"2026.11.15","pinned":true},
          {"link":"/view/112","title":"First","date":"2026.11.17","pinned":false},
          {"link":"/view/111","title":"Second","date":"2026.11.16","pinned":false}]}
        """;
  private static final String OTHER_BOARD =
      """
        {"items":[
          {"link":"/view/212","title":"Other first","date":"2026.11.17","pinned":false}]}
        """;
  private static final String POST =
      """
        {"title":"First","date":"2026.11.17","body":"Public body"}
        """;

  private NumericProfileFixture() {}

  static ProfileDriver create(LocalSite site, Clock clock) {
    site.respond("/boards/a/posts?page=1", 200, FIRST_PAGE);
    site.respond("/boards/b/posts?page=1", 200, OTHER_BOARD);
    site.respond("/view/112", 200, POST);
    site.respond(
        "/view/212",
        200,
        "{\"title\":\"Other first\",\"date\":\"2026.11.17\",\"body\":\"Other body\"}");
    var selectors =
        new Selectors(
            "/items",
            "/link",
            "/title",
            "/date",
            Optional.of("/pinned"),
            Optional.empty(),
            "/body",
            Optional.empty());
    var profile =
        new SiteProfile(
            "fixture",
            "json",
            ZoneId.of("Asia/Seoul"),
            List.of(board(site, "a", "General", "Public"), board(site, "b", "Other", "Archive")),
            new PostIdRule("/view/(\\d+)", false, List.of()),
            selectors);
    return new ProfileDriver(profile, clock);
  }

  private static BoardProfile board(LocalSite site, String id, String name, String category) {
    String postUrl = site.uri("/view/POST_ID").toString().replace("POST_ID", "{postId}");
    return new BoardProfile(
        id,
        name,
        category,
        site.uri("/boards/" + id + "/posts?page=1").toString(),
        postUrl,
        List.of("yyyy.MM.dd"));
  }
}
