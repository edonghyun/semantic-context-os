package io.scos.connector.jira;

import io.scos.connector.jira.internal.JiraValueRenderer;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira 원본 → 문서(TS source-projector/src/jira-projector.ts). `jira.` 원본 가운데 첨부·필드를 뺀 원본 하나가 문서 하나이고
 * 문서 id는 원본 externalId다. 본문은 `# 제목`, 필드마다 `이름: 값`(이슈는 names의 표시 이름), 이슈면 `## 댓글`·`## 변경 이력`·`## 작업
 * 기록`·`## 관련 링크`와 `## 첨부파일 (본문 미추출)` 목록을 빈 줄로 잇는다. 값 평문화는 {@link JiraValueRenderer}다.
 *
 * <ul>
 *   <li>제목: 이슈는 {@code `${key}: ${평문화(summary)}`}, 그 밖은 평문화(name ?? title)이고 비면 문서 id.
 *   <li>내용이 객체가 아니거나 이슈 fields가 객체가 아니면 건너뛴다(TS null). 내용을 읽을 수 없어도 건너뛴다.
 *   <li>필드 순서는 JS 객체 순서({@link JsJson#entries}), summary·comment·worklog·attachment와
 *       집계(watches·votes· progress·aggregateprogress)는 쓰지 않는다.
 *   <li>uri는 메타데이터 url(문자열)이고 없으면 {@code jira://<소스>/<문서 id>}. 시각은 공급자 시각, 없으면 관측 시각.
 * </ul>
 */
public final class JiraProjector implements Projector {
  public static final String KIND = "jira";
  private static final String PREFIX = "jira.";
  private static final String ISSUE = "jira.issue";
  private static final Set<String> NOT_DOCUMENTS = Set.of("jira.attachment", "jira.field");
  private static final Set<String> SKIPPED_FIELDS =
      Set.of(
          "summary",
          "comment",
          "worklog",
          "attachment",
          "watches",
          "votes",
          "progress",
          "aggregateprogress");
  private static final List<Map.Entry<String, String>> ISSUE_SECTIONS =
      List.of(
          Map.entry("comments", "댓글"),
          Map.entry("changelog", "변경 이력"),
          Map.entry("worklogs", "작업 기록"),
          Map.entry("remoteLinks", "관련 링크"));
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;

  public JiraProjector(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new TreeMap<>();
    for (RawItem item : items)
      if (item.resourceType().startsWith(PREFIX) && !NOT_DOCUMENTS.contains(item.resourceType()))
        groups.put(item.externalId(), List.of(item));
    return groups;
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    if (items.isEmpty() || items.getFirst().resourceType().equals("jira.attachment"))
      return Optional.empty();
    RawItem item = items.getFirst();
    Optional<JsonNode> content = reader.apply(item).map(json::readTree).filter(JsonNode::isObject);
    if (content.isEmpty()) return Optional.empty();
    boolean issue = item.resourceType().equals(ISSUE);
    JsonNode fields = issue ? content.get().get("fields") : content.get();
    if (fields == null || !fields.isObject()) return Optional.empty();
    String title = title(content.get(), fields, issue, documentId);
    List<String> lines = new ArrayList<>();
    lines.add("# " + title);
    JsonNode names = content.get().get("names");
    lines.addAll(fieldLines(fields, names != null && names.isObject() ? names : json.nullNode()));
    if (issue) lines.addAll(issueDetails(content.get(), fields));
    String observedAt = ISO_MILLIS.format(item.observedAt());
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            uri(item, sourceId, documentId),
            title,
            String.join("\n\n", lines),
            item.sourceCreatedAt().orElse(observedAt),
            item.sourceUpdatedAt().orElse(observedAt)));
  }

  private static String title(JsonNode content, JsonNode fields, boolean issue, String documentId) {
    if (issue)
      return JiraValueRenderer.templateString(content.get("key"))
          + ": "
          + JiraValueRenderer.render(fields.get("summary"));
    JsonNode name = content.get("name");
    String rendered =
        JiraValueRenderer.render(name == null || name.isNull() ? content.get("title") : name);
    return rendered.isEmpty() ? documentId : rendered;
  }

  private static List<String> fieldLines(JsonNode fields, JsonNode names) {
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, JsonNode> field : JsJson.entries(fields)) {
      if (SKIPPED_FIELDS.contains(field.getKey())) continue;
      String text = JiraValueRenderer.render(field.getValue());
      if (text.isEmpty()) continue;
      JsonNode name = names.get(field.getKey());
      lines.add((name != null && name.isString() ? name.asString() : field.getKey()) + ": " + text);
    }
    return lines;
  }

  private static List<String> issueDetails(JsonNode content, JsonNode fields) {
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, String> section : ISSUE_SECTIONS) {
      String text = JiraValueRenderer.render(content.get(section.getKey()));
      if (!text.isEmpty()) lines.add("## " + section.getValue() + "\n" + text);
    }
    JsonNode attachments = fields.get("attachment");
    if (attachments != null && attachments.isArray()) {
      lines.add("## 첨부파일 (본문 미추출)");
      for (JsonNode attachment : attachments)
        if (attachment.isObject())
          lines.add(
              JiraValueRenderer.render(attachment.get("filename"))
                  + " ("
                  + JiraValueRenderer.render(attachment.get("mimeType"))
                  + ", "
                  + JiraValueRenderer.render(attachment.get("size"))
                  + " bytes)");
    }
    return lines;
  }

  private String uri(RawItem item, String sourceId, String documentId) {
    JsonNode url = json.readTree(item.metadataJson()).get("url");
    return url != null && url.isString() ? url.asString() : "jira://" + sourceId + "/" + documentId;
  }
}
