package io.scos.boardarchive.profile;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.fetch.*;
import io.scos.boardarchive.testkit.LocalSite;
import io.scos.boardarchive.testkit.MutableClock;
import io.scos.boardarchive.testsupport.*;
import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

final class ProfileScenario implements AutoCloseable {
  final LocalSite site = new LocalSite();
  final MutableClock clock = new MutableClock();
  final HttpClient client = HttpClient.newHttpClient();
  final PoliteFetcher http =
      new PoliteFetcher(
          client, clock, clock::advance, SitePolicy.defaults("archive-test"), new FetchLog());
  private ProfileDriver driver;
  private Optional<List<String>> identityOverride = Optional.empty();

  ProfileScenario() throws Exception {
    use("html", false);
  }

  void useIdentitySelectors(List<String> selectors) {
    identityOverride = Optional.of(selectors);
    use("json", false);
  }

  void use(String format, boolean prefix) {
    boolean html = format.equals("html");
    Map<String, Object> selectors = selectors(html);
    Map<String, Object> board = boardProfile();
    var json =
        new JsonMapper()
            .writeValueAsString(
                Map.of(
                    "site",
                    "example-board",
                    "format",
                    format,
                    "zone",
                    "Asia/Seoul",
                    "boards",
                    List.of(board),
                    "postId",
                    Map.of(
                        "urlPattern",
                        "/post/(\\d+)",
                        "boardPrefix",
                        prefix,
                        "dropQueryParams",
                        List.of("track")),
                    "selectors",
                    selectors));
    driver = new ProfileDriver(SiteProfile.parse(json), clock);
    site.respond("/list?page=1", 200, resource(html ? "list-1.html" : "list.json"));
    site.respond("/list?page=2", 200, resource(html ? "list-2.html" : "list.json"));
    site.respond("/post/12?keep=y", 200, resource(html ? "post-1.html" : "post.json"));
    site.respond("/post/12", 200, resource(html ? "post-1.html" : "post.json"));
  }

  private Map<String, Object> boardProfile() {
    Map<String, Object> board =
        Map.of(
            "id",
            "notice",
            "name",
            "Notice",
            "category",
            "",
            "listUrl",
            site.uri("/list?page=1").toString(),
            "postUrl",
            site.uri("/post/{postId}".replace("{postId}", "POST_ID"))
                .toString()
                .replace("POST_ID", "{postId}"),
            "datePatterns",
            List.of("yyyy.MM.dd"));
    return board;
  }

  private Map<String, Object> selectors(boolean html) {
    Map<String, Object> selectors = new HashMap<>();
    selectors.put("listItem", html ? "li.post" : "/items");
    selectors.put("link", html ? "a.title" : "/link");
    selectors.put("title", html ? ".title" : "/title");
    selectors.put("date", html ? ".date" : "/date");
    selectors.put("pinned", html ? ".pinned" : "/pinned");
    selectors.put("nextPage", html ? "a.next" : "/next");
    selectors.put("body", html ? ".body" : "/body");
    selectors.put("attachments", html ? "a.attachment" : "/attachments");
    selectors.put("author", html ? ".author" : "/author");
    selectors.put(
        "identitySelectors",
        identityOverride.orElse(
            html ? List.of(".private-contact") : List.of("/metadata/privateField")));
    selectors.put("identityFields", List.of("privateKey"));
    return selectors;
  }

  private String resource(String name) {
    try (var in = ProfileScenario.class.getResourceAsStream("/profile/" + name)) {
      if (in == null) throw new IllegalStateException("Missing profile fixture");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  ListPage list() {
    return driver.listPosts(http, driver.boards(http).getFirst(), Optional.empty());
  }

  ListPage next(String cursor) {
    return driver.listPosts(http, driver.boards(http).getFirst(), Optional.of(cursor));
  }

  FetchResult fetch() {
    return driver.fetchPost(http, driver.boards(http).getFirst(), "12");
  }

  String attachmentUrl() {
    return site.uri("/files/a.pdf").toString();
  }

  String canonicalPostUrl() {
    return site.uri("/post/12?keep=y").toString();
  }

  @Override
  public void close() {
    client.close();
    site.close();
  }
}
