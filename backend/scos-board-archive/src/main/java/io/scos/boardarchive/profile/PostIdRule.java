package io.scos.boardarchive.profile;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public record PostIdRule(String urlPattern, boolean boardPrefix, List<String> dropQueryParams) {
  public PostIdRule {
    if (Pattern.compile(urlPattern).matcher("").groupCount() < 1)
      throw new IllegalArgumentException("Post id pattern needs group 1");
    dropQueryParams = List.copyOf(dropQueryParams);
  }

  String id(URI url, String board) {
    Matcher m = Pattern.compile(urlPattern).matcher(url.toString());
    if (!m.find()) throw new IllegalArgumentException("Post URL does not match id rule");
    String id = Objects.requireNonNull(m.group(1));
    if (id.isBlank()) throw new IllegalArgumentException("Empty post id");
    return boardPrefix ? board + "-" + id : id;
  }

  String localId(String board, String id) {
    if (!boardPrefix) return id;
    if (!id.startsWith(board + "-"))
      throw new IllegalArgumentException("Post id belongs to another board");
    return id.substring(board.length() + 1);
  }

  URI canonical(URI uri) {
    String query = uri.getRawQuery();
    String retained =
        query == null
            ? ""
            : Arrays.stream(query.split("&", -1))
                .filter(
                    p ->
                        !dropQueryParams.contains(
                            URLDecoder.decode(p.split("=", 2)[0], StandardCharsets.UTF_8)))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    return URI.create(
        uri.getScheme()
            + "://"
            + uri.getRawAuthority()
            + uri.getRawPath()
            + (retained.isEmpty() ? "" : "?" + retained));
  }
}
