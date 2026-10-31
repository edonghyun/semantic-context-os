package io.scos.mcp;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** provenance.uri → 출처 분류. 파싱 실패는 unknown. */
enum SourceKind {
  SLACK,
  NOTION,
  JIRA,
  GOOGLE_DRIVE,
  TRANSCRIPT,
  MEMORY,
  DOCUMENT,
  GIT,
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
    return switch (scheme.toLowerCase(Locale.ROOT)) {
      case "slack" -> SLACK;
      case "notion" -> NOTION;
      case "jira" -> JIRA;
      case "gdrive" -> GOOGLE_DRIVE;
      case "claude-transcript" -> TRANSCRIPT;
      case "claude-memory" -> MEMORY;
      case "file" -> DOCUMENT;
      case "git" -> GIT;
      case "https" -> web(u, true);
      case "http" -> web(u, false);
      default -> UNKNOWN;
    };
  }

  /** 웹 주소: Drive·Jira는 https만, Notion은 http도. */
  private static SourceKind web(URI u, boolean https) {
    String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
    String path = u.getPath() == null ? "" : u.getPath();
    if (https && (host.equals("drive.google.com") || host.equals("docs.google.com")))
      return GOOGLE_DRIVE;
    if (https && host.endsWith(".atlassian.net") && isJiraPath(path)) return JIRA;
    if (NOTION_HOSTS.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d)))
      return NOTION;
    return UNKNOWN;
  }

  /** 사이트 첫 화면, 이슈(/browse/), Jira 앱(/jira/) — 위키(/wiki/)는 아니다. */
  private static boolean isJiraPath(String path) {
    return path.isEmpty()
        || path.equals("/")
        || path.startsWith("/browse/")
        || path.startsWith("/jira/");
  }
}
