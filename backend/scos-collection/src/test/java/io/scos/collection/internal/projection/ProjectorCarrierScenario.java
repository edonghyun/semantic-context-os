package io.scos.collection.internal.projection;

import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.googledrive.GoogleDriveProjector;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.jira.JiraProjector;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.notion.NotionProjector;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.SlackProjector;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.connector.tiro.TiroProjector;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.projection.Projector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** 실제 커넥터의 carrier 선언과 투영 묶음을 연결한다. 공급자 API는 호출하지 않는다. */
final class ProjectorCarrierScenario {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
  private static final String LOCAL_URL = "http://127.0.0.1:1";
  private final Projector projector;
  private final SourceAdapter adapter;
  private final List<RawItem> items;

  private ProjectorCarrierScenario(
      Projector projector, SourceAdapter adapter, List<RawItem> items) {
    this.projector = projector;
    this.adapter = adapter;
    this.items = items;
  }

  static ProjectorCarrierScenario slack() {
    return new ProjectorCarrierScenario(
        new SlackProjector(JSON),
        SlackAssembly.adapter(LOCAL_URL, "synthetic", CLOCK),
        List.of(
            message("C1", "1760400000.000100", Optional.empty()),
            message("C1", "1760400000.000200", Optional.empty()),
            message("C1", "1760400000.000300", Optional.of("1760400000.000300")),
            message("C1", "1760400000.000400", Optional.of("1760400000.000300")),
            message("C2", "1760400000.000100", Optional.empty())));
  }

  static ProjectorCarrierScenario slackWithMetadata(String metadata) {
    return new ProjectorCarrierScenario(
        new SlackProjector(JSON),
        SlackAssembly.adapter(LOCAL_URL, "synthetic", CLOCK),
        List.of(
            RawItems.item("C1:1760400000.000100")
                .resourceType("slack.message")
                .metadata(metadata)
                .build()));
  }

  static ProjectorCarrierScenario drive() {
    return drive(
        List.of(
            sheet("F1"),
            item("gdrive.file", "file:F1"),
            item("gdrive.file", "file:F2"),
            item("gdrive.drive", "drive:D1"),
            sheet("missing")));
  }

  static ProjectorCarrierScenario jira() {
    return jira(
        List.of(
            item("jira.issue", "issue:ACME-1"),
            item("jira.project", "project:P1"),
            item("jira.component", "component:C1"),
            item("jira.version", "version:V1"),
            item("jira.filter", "filter:F1"),
            item("jira.dashboard", "dashboard:D1"),
            item("jira.board", "board:B1"),
            item("jira.sprint", "sprint:S1"),
            item("jira.attachment", "attachment:A1"),
            item("jira.field", "field:F1")));
  }

  static ProjectorCarrierScenario tiro() {
    return new ProjectorCarrierScenario(
        new TiroProjector(JSON),
        TiroAssembly.adapter(LOCAL_URL, "synthetic", CLOCK),
        List.of(item("tiro.note", "note:N1"), item("tiro.note", "note:N2")));
  }

  static ProjectorCarrierScenario notion() {
    return new ProjectorCarrierScenario(
        new NotionProjector(JSON),
        NotionAssembly.adapter(LOCAL_URL, "synthetic", CLOCK),
        List.of(item("notion.page", "P1")));
  }

  Set<String> invalidAfterOmittingCarrier(String externalId) {
    Map<String, List<RawItem>> incomplete = new TreeMap<>();
    projector
        .groupIntoDocuments(items)
        .forEach(
            (id, bundle) ->
                incomplete.put(
                    id,
                    bundle.stream()
                        .filter(item -> !item.externalId().equals(externalId))
                        .toList()));
    return adapter.accessPolicy().invalidDocuments(items, incomplete);
  }

  Set<String> invalidDocuments() {
    return adapter.accessPolicy().invalidDocuments(items, projector.groupIntoDocuments(items));
  }

  static ProjectorCarrierScenario driveFragments() {
    return drive(List.of(sheet("F1")));
  }

  static ProjectorCarrierScenario slackFragments() {
    return new ProjectorCarrierScenario(
        new SlackProjector(JSON),
        SlackAssembly.adapter(LOCAL_URL, "synthetic", CLOCK),
        List.of(
            RawItems.item("file:F1")
                .resourceType("slack.file")
                .metadata("{\"channelId\":\"C3\",\"messageTs\":\"1760400000.000100\"}")
                .build()));
  }

  static ProjectorCarrierScenario jiraFragments() {
    return jira(List.of(item("jira.attachment", "attachment:A1")));
  }

  Map<String, List<String>> groupedCarrierIds() {
    Map<String, List<String>> result = new TreeMap<>();
    projector
        .groupIntoDocuments(items)
        .forEach(
            (document, bundle) ->
                result.put(
                    document,
                    bundle.stream()
                        .filter(item -> adapter.accessPolicy().carries(item))
                        .map(RawItem::externalId)
                        .sorted()
                        .toList()));
    return result;
  }

  Set<String> documentIds() {
    return projector.groupIntoDocuments(items).keySet();
  }

  private static ProjectorCarrierScenario drive(List<RawItem> items) {
    return new ProjectorCarrierScenario(
        new GoogleDriveProjector(JSON),
        GoogleDriveAssembly.adapter(LOCAL_URL, GoogleDriveAssembly.CREDENTIAL_JSON, CLOCK),
        items);
  }

  private static ProjectorCarrierScenario jira(List<RawItem> items) {
    return new ProjectorCarrierScenario(
        new JiraProjector(JSON), JiraAssembly.adapter(LOCAL_URL, "synthetic", CLOCK), items);
  }

  private static RawItem item(String type, String id) {
    return RawItems.item(id).resourceType(type).metadata("{}").build();
  }

  private static RawItem sheet(String file) {
    return RawItems.item("sheet:" + file + ":S1")
        .resourceType("gdrive.sheet")
        .metadata(JSON.writeValueAsString(Map.of("fileId", file)))
        .build();
  }

  private static RawItem message(String channel, String ts, Optional<String> thread) {
    String threadJson = thread.map(value -> "\"" + value + "\"").orElse("null");
    return RawItems.item(channel + ":" + ts)
        .metadata(
            "{\"channelId\":\""
                + channel
                + "\",\"messageTs\":\""
                + ts
                + "\",\"threadTs\":"
                + threadJson
                + ",\"authorId\":\"U1\"}")
        .build();
  }
}
