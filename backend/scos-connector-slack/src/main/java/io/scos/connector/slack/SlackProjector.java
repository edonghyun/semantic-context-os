package io.scos.connector.slack;

import io.scos.connector.slack.internal.SlackMrkdwnCleaner;
import io.scos.connector.slack.internal.SlackTs;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack 원본 항목 → 스레드/일별 문서(G2). 문서 id `channel/thread-<ts>` 또는 `channel/day-<UTC>`, 본문은 ts 순
 * `[HH:MM] author: cleaned`(빈 본문 제외, 전부 비면 empty), 제목은 첫 유의미 줄 80자(UTF-16) 또는 `#channel …`. 항목 순서를
 * 섞어도 결과 동일.
 */
public final class SlackProjector implements Projector {
  public static final String KIND = "slack";
  static final String RESOURCE_TYPE = "slack.message";
  private static final Pattern NEWLINE = Pattern.compile("\n");
  private static final Pattern WS_RUN = Pattern.compile(Text.whitespaceClass() + "+");
  private static final Pattern PUNCT_ONLY = Pattern.compile("^[\\p{P}\\p{S}]+$");
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final ObjectMapper json;
  private final SlackMrkdwnCleaner cleaner = new SlackMrkdwnCleaner();

  public SlackProjector(ObjectMapper json) {
    this.json = json;
  }

  private Optional<String> meta(RawItem item, String field) {
    JsonNode node = json.readTree(item.metadataJson()).path(field);
    return node.isString() ? Optional.of(node.asString()) : Optional.empty();
  }

  private Optional<SlackTs> messageTs(RawItem item) {
    return meta(item, "messageTs").flatMap(SlackTs::parse);
  }

  private Optional<SlackTs> threadTs(RawItem item) {
    return meta(item, "threadTs").flatMap(SlackTs::parse);
  }

  private boolean belongsToSlack(RawItem item) {
    return item.resourceType().equals(RESOURCE_TYPE)
        && meta(item, "channelId").isPresent()
        && messageTs(item).isPresent();
  }

  @Override
  public String kind() {
    return KIND;
  }

  /** documentId 코드 유닛 순. */
  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    Map<String, List<RawItem>> groups = new TreeMap<>();
    for (RawItem item : items) {
      if (!belongsToSlack(item)) continue;
      String channelId = meta(item, "channelId").orElseThrow();
      String documentId =
          threadTs(item)
              .map(t -> channelId + "/thread-" + t.text())
              .orElseGet(() -> channelId + "/day-" + utcDate(messageTs(item).orElseThrow()));
      groups.computeIfAbsent(documentId, k -> new ArrayList<>()).add(item);
    }
    return groups;
  }

  @Override
  public Optional<SourceDocument> project(
      String sourceId, String documentId, List<RawItem> items, ContentReader reader) {
    List<RawItem> ordered =
        items.stream()
            .sorted(Comparator.comparing(i -> messageTs(i).orElseThrow(), SlackTs.CHRONOLOGICAL))
            .toList();
    List<String> lines = new ArrayList<>();
    List<String> cleanedTexts = new ArrayList<>();
    for (RawItem item : ordered) {
      Optional<String> text = reader.apply(item).flatMap(this::textOf);
      String cleaned = text.map(cleaner::clean).orElse(null);
      cleanedTexts.add(cleaned);
      if (cleaned == null || Text.jsTrim(cleaned).isEmpty()) continue;
      String author = meta(item, "authorId").orElse("unknown");
      lines.add(
          "[" + utcHourMinute(messageTs(item).orElseThrow()) + "] " + author + ": " + cleaned);
    }
    if (lines.isEmpty()) return Optional.empty();
    String body = String.join("\n", lines);
    String channelId = documentId.substring(0, documentId.indexOf('/'));
    int threadAt = documentId.indexOf("/thread-");
    String title =
        threadAt >= 0
            ? threadTitle(
                cleanedTexts, documentId.substring(threadAt + "/thread-".length()), channelId)
            : "#"
                + channelId
                + " "
                + documentId.substring(documentId.indexOf("/day-") + "/day-".length());
    List<SlackTs> tsList = ordered.stream().map(i -> messageTs(i).orElseThrow()).toList();
    List<SlackTs> versions =
        ordered.stream().map(i -> SlackTs.parse(i.sourceVersion()).orElseThrow()).toList();
    String createdAt = isoSeconds(extreme(tsList, true));
    String updatedAt = isoSeconds(extreme(versions, false));
    return Optional.of(
        new SourceDocument(
            sourceId,
            documentId,
            "slack://" + sourceId + "/" + documentId,
            title,
            body,
            createdAt,
            updatedAt));
  }

  private Optional<String> textOf(String contentJson) {
    JsonNode text = json.readTree(contentJson).path("text");
    return text.isString() ? Optional.of(text.asString()) : Optional.empty();
  }

  /** 첫 유의미 줄(트림 후 비어 있지 않고 @…/#… 토큰·구두점만인 줄이 아님) 80자, 없으면 `#channel thread <ts>`. */
  private static String threadTitle(List<String> cleanedTexts, String threadTs, String channelId) {
    for (String cleaned : cleanedTexts) {
      if (cleaned == null) continue;
      for (String line : NEWLINE.split(cleaned, -1)) {
        String trimmed = Text.jsTrim(line);
        if (trimmed.isEmpty()) continue;
        boolean meaningful = false;
        for (String token : WS_RUN.split(trimmed, -1))
          if (!token.isEmpty()
              && !token.startsWith("@")
              && !token.startsWith("#")
              && !PUNCT_ONLY.matcher(token).matches()) meaningful = true;
        if (meaningful) return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
      }
    }
    return "#" + channelId + " thread " + threadTs;
  }

  private static SlackTs extreme(List<SlackTs> tsList, boolean min) {
    SlackTs best = tsList.get(0);
    for (SlackTs ts : tsList) {
      int cmp = SlackTs.CHRONOLOGICAL.compare(ts, best);
      if (min ? cmp < 0 : cmp > 0) best = ts;
    }
    return best;
  }

  private static String utcDate(SlackTs ts) {
    return isoSeconds(ts).substring(0, 10);
  }

  private static String utcHourMinute(SlackTs ts) {
    return isoSeconds(ts).substring(11, 16);
  }

  private static String isoSeconds(SlackTs ts) {
    return ISO_MILLIS.format(Instant.ofEpochSecond(ts.epochSecond()));
  }
}
