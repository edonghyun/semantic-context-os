package io.scos.boardarchive.driver;

import io.scos.boardarchive.profile.*;
import io.scos.boardarchive.testkit.LocalSite;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class ProfileDriverFixtures {
  static ProfileDriver createHtml(LocalSite site, Clock clock) {
    return create(
        site,
        clock,
        "html",
        new Selectors(
            "li.post",
            "a.title",
            ".title",
            ".date",
            Optional.of(".pinned"),
            Optional.of("a.next"),
            ".body",
            Optional.empty()));
  }

  static ProfileDriver createJson(LocalSite site, Clock clock) {
    return create(
        site,
        clock,
        "json",
        new Selectors(
            "/items",
            "/link",
            "/title",
            "/date",
            Optional.of("/pinned"),
            Optional.of("/next"),
            "/body",
            Optional.empty()));
  }

  private static ProfileDriver create(
      LocalSite site, Clock clock, String format, Selectors selectors) {
    var profile =
        new SiteProfile(
            "example-board",
            format,
            ZoneId.of("Asia/Seoul"),
            List.of(board(site, "a", "General", "Public"), board(site, "b", "Other", "Archive")),
            new PostIdRule("/posts/[ab]-(\\d+)", true, List.of()),
            selectors);
    site.respond("/list/a?offset=0", 200, resource(format, "list-a-1"));
    site.respond("/list/a?offset=3", 200, resource(format, "list-a-2"));
    site.respond("/list/b?offset=0", 200, resource(format, "list-b"));
    site.respond("/posts/a-12", 200, resource(format, "post"));
    site.respond("/posts/b-12", 200, resource(format, "post-b"));
    return new ProfileDriver(profile, clock);
  }

  private static BoardProfile board(LocalSite site, String id, String name, String category) {
    String template =
        site.uri("/posts/" + id + "-POST_ID").toString().replace("POST_ID", "{postId}");
    return new BoardProfile(
        id,
        name,
        category,
        site.uri("/list/" + id + "?offset=0").toString(),
        template,
        List.of("yyyy.MM.dd"));
  }

  private static String resource(String format, String name) {
    String path = "/driver-contract/" + format + "/" + name + "." + format;
    try (var in = ProfileDriverFixtures.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("given 준비 실패: 계약 고정 자료가 없다: " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
