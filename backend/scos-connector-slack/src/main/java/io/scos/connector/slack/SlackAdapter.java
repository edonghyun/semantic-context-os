package io.scos.connector.slack;

import io.scos.connector.slack.internal.SlackAccessCollection;
import io.scos.connector.slack.internal.SlackCheckpoint;
import io.scos.connector.slack.internal.SlackCollection;
import io.scos.connector.slack.internal.SlackConfiguration;
import io.scos.connector.slack.internal.SlackWebApiClient;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.ListingField;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slack 폴링 어댑터(ADR-0018 §2·F12): 참여 채널 발견 → 채널 순서 → history 페이지(워터마크 + overlap) → 스레드 replies →
 * 워터마크 확정. 항목 키 `channelId:ts`, sourceVersion = edited.ts ?? ts, 순서 스킴 slack.message-state.v1. 실제
 * 걷기는 run 하나가 소유하는 {@link SlackCollection}이 한다.
 */
public final class SlackAdapter implements SourceAdapter {
  public static final String KIND = "slack";

  private final CredentialResolver credentials;
  private final SlackWebApiClient client;
  private final ObjectMapper json;
  private final Clock clock;
  private final AccessPolicy access;

  public SlackAdapter(
      CredentialResolver credentials, SlackWebApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
    SlackAccessCollection collection = new SlackAccessCollection(credentials, client, clock);
    this.access =
        new SlackProjector(json)
            .accessPolicy()
            .collecting(
                collection::collect,
                (source, id, at) -> Optional.of(collection.aclFromExternalId(source, id, at)));
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public AccessPolicy accessPolicy() {
    return access;
  }

  @Override
  public void validateConfig(SourceConfig config) {
    SlackConfiguration.read(json, config);
  }

  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    SlackConfiguration c = SlackConfiguration.read(json, config);
    return mode == CollectionMode.SNAPSHOT && c.historySince().isEmpty()
        ? CollectionCoverage.COMPLETE_SNAPSHOT
        : CollectionCoverage.INCREMENTAL;
  }

  /** 이벤트 서명 비밀이 설정되면 삭제를 공급자 이벤트로 안다. */
  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return SlackConfiguration.read(json, config).eventSigningSecret()
        ? DeletionDetection.PROVIDER_EVENTS
        : DeletionDetection.NONE;
  }

  /** {conversationTypes, channelIds?, history: "all" | {since: ISO 밀리초}}. */
  @Override
  public List<ListingField> listingFields(SourceConfig config) {
    SlackConfiguration c = SlackConfiguration.read(json, config);
    ObjectNode selection = json.createObjectNode();
    ArrayNode types = selection.putArray("conversationTypes");
    c.conversationTypes().forEach(types::add);
    c.channelIds()
        .ifPresent(
            ids -> {
              ArrayNode channels = selection.putArray("channelIds");
              ids.forEach(channels::add);
            });
    if (c.historySince().isEmpty()) selection.put("history", "all");
    else selection.putObject("history").put("since", c.historySince().get().toIsoMillis());
    return List.of(new ListingField("slackSelection", json.writeValueAsString(selection)));
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    if (input.cancel().isCancelled())
      throw new CallFailureException(
          "slack.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
    SlackConfiguration config = SlackConfiguration.read(json, input.source().config());
    SlackCheckpoint resumed =
        SlackCheckpoint.resume(json, input.checkpoint(), input.mode(), config.historySince());
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    new SlackCollection(input, config, resumed, token, client, json, clock).collect(sink);
  }
}
