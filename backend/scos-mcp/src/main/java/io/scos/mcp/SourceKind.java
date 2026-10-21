package io.scos.mcp;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** provenance.uri → 출처 분류(TS classifySource와 같은 규칙). 파싱 실패는 unknown. */
enum SourceKind {
  SLACK,
  NOTION,
  JIRA,
  GOOGLE_DRIVE,
  UNKNOWN;

  private static final List<String> NOTION_HOSTS =
      List.of("notion.so", "app.notion.com", "notion.site");

  String wire() {
    return this == GOOGLE_DRIVE ? "google-drive" : name().toLowerCase(Locale.ROOT);
  }

  static SourceKind of(String uri) {
    URI u;
    try {
      u = URI.create(uri);
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
    String scheme = u.getScheme();
    if (scheme == null) return UNKNOWN;
    scheme = scheme.toLowerCase(Locale.ROOT);
    switch (scheme) {
      case "slack" -> {
        return SLACK;
      }
      case "notion" -> {
        return NOTION;
      }
      case "jira" -> {
        return JIRA;
      }
      case "gdrive" -> {
        return GOOGLE_DRIVE;
      }
      default -> {}
    }
    String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
    String path = u.getPath() == null ? "" : u.getPath();
    if (scheme.equals("https")
        && (host.equals("drive.google.com") || host.equals("docs.google.com"))) return GOOGLE_DRIVE;
    if (scheme.equals("https")
        && host.endsWith(".atlassian.net")
        && (path.isEmpty()
            || path.equals("/")
            || path.startsWith("/browse/")
            || path.startsWith("/jira/"))) return JIRA;
    if ((scheme.equals("http") || scheme.equals("https"))
        && NOTION_HOSTS.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d)))
      return NOTION;
    return UNKNOWN;
  }
}
