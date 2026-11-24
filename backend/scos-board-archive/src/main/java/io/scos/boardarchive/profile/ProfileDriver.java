package io.scos.boardarchive.profile;

import io.scos.boardarchive.driver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic profiles strip identity metadata; custom drivers own any additional site-specific
 * identity fields.
 */
public final class ProfileDriver implements BoardDriver {
  private final SiteProfile profile;
  private final Clock clock;
  private final Map<String, URI> urls = new HashMap<>();
  private final Map<String, String> listedDates = new HashMap<>();

  public ProfileDriver(SiteProfile profile, Clock clock) {
    this.profile = profile;
    this.clock = clock;
  }

  @Override
  public String site() {
    return profile.site();
  }

  @Override
  public List<Board> boards(Fetcher http) {
    return profile.boards().stream().map(b -> new Board(b.id(), b.name(), b.category())).toList();
  }

  @Override
  public ListPage listPosts(Fetcher http, Board board, Optional<String> cursor) {
    BoardProfile b = board(board.id());
    URI uri =
        cursor.map(URI::create).orElseGet(() -> URI.create(b.listUrl().replace("{cursor}", "")));
    ProfilePage page =
        ProfilePage.parse(
            profile.format(), http.get(uri).requireOk().body(), uri, profile.selectors());
    List<PostSummary> posts = new ArrayList<>();
    for (ProfilePage item : page.select(profile.selectors().listItem()))
      posts.add(summary(b, item, uri));
    Optional<String> next =
        profile
            .selectors()
            .nextPage()
            .flatMap(s -> page.select(s).stream().findFirst())
            .map(ProfilePage::link)
            .filter(v -> !v.isBlank())
            .map(v -> uri.resolve(v).toString());
    return new ListPage(posts, next);
  }

  private PostSummary summary(BoardProfile board, ProfilePage item, URI base) {
    Selectors s = profile.selectors();
    URI uri = profile.postId().canonical(base.resolve(required(item, s.link()).link()));
    String id = profile.postId().id(uri, board.id());
    urls.put(id, uri);
    listedDates.put(id, required(item, s.date()).text());
    boolean pinned =
        s.pinned()
            .map(sel -> item.select(sel).stream().anyMatch(ProfilePage::marked))
            .orElse(false);
    return new PostSummary(
        id,
        required(item, s.title()).text(),
        dates(board).parse(required(item, s.date()).text()),
        Optional.empty(),
        pinned);
  }

  @Override
  public FetchResult fetchPost(Fetcher http, Board board, String postId) {
    BoardProfile b = board(board.id());
    URI uri = urls.getOrDefault(postId, postUri(b, postId));
    Response response = http.get(uri);
    if (response.status() == 404) return new FetchResult.Deleted();
    if (response.status() == 403) return new FetchResult.AccessDenied();
    if (response.status() < 200 || response.status() >= 300)
      return new FetchResult.Unknown("HTTP " + response.status());
    try {
      return found(b, postId, uri, response.body());
    } catch (IllegalArgumentException e) {
      return new FetchResult.Unknown("Post fields could not be parsed");
    }
  }

  private FetchResult.Found found(BoardProfile board, String postId, URI uri, String raw) {
    ProfilePage page = ProfilePage.parse(profile.format(), raw, uri, profile.selectors());
    Selectors s = profile.selectors();
    List<String> attachments =
        s.attachments()
            .map(
                sel ->
                    page.select(sel).stream()
                        .map(ProfilePage::link)
                        .map(uri::resolve)
                        .map(profile.postId()::canonical)
                        .map(URI::toString)
                        .toList())
            .orElse(List.of());
    return new FetchResult.Found(
        uri.toString(),
        rawMetadata(page, postId),
        required(page, s.title()).text(),
        required(page, s.body()).text(),
        s.author()
            .flatMap(sel -> page.select(sel).stream().findFirst())
            .map(ProfilePage::text)
            .orElse(""),
        dates(board).parse(required(page, s.date()).text()),
        attachments,
        List.of(),
        true);
  }

  private String rawMetadata(ProfilePage page, String postId) {
    String content = page.sanitizedRaw();
    if (!listedDates.containsKey(postId)) return content;
    return JsonMapper.builder()
        .build()
        .writeValueAsString(
            Map.of(
                "content", content, "listedDate", Objects.requireNonNull(listedDates.get(postId))));
  }

  private URI postUri(BoardProfile board, String id) {
    String local =
        URLEncoder.encode(profile.postId().localId(board.id(), id), StandardCharsets.UTF_8)
            .replace("+", "%20");
    return profile.postId().canonical(URI.create(board.postUrl().replace("{postId}", local)));
  }

  private BoardProfile board(String id) {
    return profile.boards().stream()
        .filter(b -> b.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown board"));
  }

  private DateParser dates(BoardProfile board) {
    return new DateParser(profile.zone(), board.datePatterns(), clock);
  }

  private ProfilePage required(ProfilePage page, String selector) {
    return page.select(selector).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Required profile field absent"));
  }
}
