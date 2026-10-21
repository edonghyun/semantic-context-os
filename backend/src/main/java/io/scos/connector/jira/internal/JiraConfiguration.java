package io.scos.connector.jira.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira 소스 설정(TS readJiraConfiguration, jira-adapter.ts:78-115): schemaVersion 1, siteUrl은 Atlassian
 * Cloud 사이트 origin(https, `*.atlassian.net`, 포트·사용자 정보·쿼리·프래그먼트·경로 없음 — 인증정보를 보낼 곳이다), email(@ 포함,
 * 콜론·줄바꿈 없음 — Basic 인증의 사용자 부분), cloudId(선택, 소문자 UUID — 있으면 cloud gateway로 부른다),
 * downloadAttachments(선택, 기본 true). 거부 메시지는 TS와 같다.
 */
public record JiraConfiguration(
    String siteUrl, String email, Optional<String> cloudId, boolean downloadAttachments) {
  static final String GATEWAY = "https://api.atlassian.com/ex/jira/";
  private static final Pattern SITE_HOST = Pattern.compile("^[a-z0-9-]+\\.atlassian\\.net$");
  private static final Pattern CLOUD_ID = Pattern.compile("^[a-f0-9-]{36}$");
  private static final Pattern EMAIL_FORBIDDEN = Pattern.compile("[:\\r\\n]");
  private static final int HTTPS_DEFAULT_PORT = 443;

  public static JiraConfiguration read(ObjectMapper json, SourceConfig config) {
    JsonNode value = json.readTree(config.valueJson());
    if (value == null || !value.isObject()) throw invalid("Invalid Jira object");
    JsonNode site = value.get("siteUrl");
    JsonNode email = value.get("email");
    if (config.schemaVersion() != 1
        || site == null
        || !site.isString()
        || email == null
        || !email.isString()
        || !email.asString().contains("@")
        || EMAIL_FORBIDDEN.matcher(email.asString()).find())
      throw invalid("Invalid Jira source configuration");
    String origin = siteOrigin(site.asString());
    Optional<String> cloudId = Optional.empty();
    if (value.has("cloudId")) {
      JsonNode id = value.get("cloudId");
      if (!id.isString() || !CLOUD_ID.matcher(id.asString()).matches())
        throw invalid("Invalid Jira cloudId");
      cloudId = Optional.of(id.asString());
    }
    boolean downloadAttachments = true;
    if (value.has("downloadAttachments")) {
      JsonNode download = value.get("downloadAttachments");
      if (!download.isBoolean()) throw invalid("Invalid Jira attachment setting");
      downloadAttachments = download.asBoolean();
    }
    return new JiraConfiguration(origin, email.asString(), cloudId, downloadAttachments);
  }

  /** API를 부를 곳: cloudId가 있으면 cloud gateway, 없으면 사이트 origin. */
  String apiBase() {
    return cloudId.map(id -> GATEWAY + id).orElse(siteUrl);
  }

  /**
   * TS `new URL(siteUrl)` 검사와 `url.origin`. 호스트는 소문자, https 기본 포트(443)는 없는 것과 같다. 해석은 WHATWG URL보다
   * 엄격하다 — 공백·역슬래시·빈 사용자 정보·전각 호스트 등은 거부한다(CONTRACTS "Jira 커넥터 편차").
   */
  private static String siteOrigin(String siteUrl) {
    URI url;
    try {
      url = new URI(siteUrl);
    } catch (URISyntaxException e) {
      throw invalid("Invalid URL");
    }
    if (url.getScheme() == null || url.getHost() == null) throw invalid("Invalid URL");
    String host = url.getHost().toLowerCase(Locale.ROOT);
    String path = url.getRawPath() == null ? "" : url.getRawPath();
    boolean defaultPort = url.getPort() == -1 || url.getPort() == HTTPS_DEFAULT_PORT;
    if (!"https".equalsIgnoreCase(url.getScheme())
        || !SITE_HOST.matcher(host).matches()
        || !defaultPort
        || url.getRawUserInfo() != null
        || (url.getRawQuery() != null && !url.getRawQuery().isEmpty())
        || (url.getRawFragment() != null && !url.getRawFragment().isEmpty())
        || !(path.isEmpty() || path.equals("/")))
      throw invalid("Jira siteUrl must be an Atlassian Cloud site origin");
    return "https://" + host;
  }

  private static CallFailureException invalid(String message) {
    return new CallFailureException(
        "jira.configuration", CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, message));
  }
}
