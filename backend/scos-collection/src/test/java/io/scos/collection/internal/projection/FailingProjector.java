package io.scos.collection.internal.projection;

import io.scos.connector.slack.SlackProjector;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** 실제 Slack 투영기의 문서 렌더링·그룹 묶기에만 고장을 주입한다. */
final class FailingProjector implements Projector {
  private final Projector delegate;
  private Optional<String> failed = Optional.empty();
  private Optional<String> empty = Optional.empty();
  private boolean groupingFailed;
  private Optional<String> omittedCarrier = Optional.empty();
  private boolean wrongDocument;
  private boolean wrongSource;
  private boolean consumeInput;
  private boolean omitAllBundles;

  void omitAllBundles() {
    omitAllBundles = true;
  }

  void consumeInput() {
    consumeInput = true;
  }

  void omitCarrier(String externalId) {
    omittedCarrier = Optional.of(externalId);
  }

  void changeDocumentId() {
    wrongDocument = true;
  }

  void changeSourceId() {
    wrongSource = true;
  }

  private Runnable next = () -> {};

  FailingProjector(ObjectMapper json) {
    delegate = new SlackProjector(json);
  }

  void failDocument(String id) {
    failed = Optional.of(id);
  }

  void emptyDocument(String id) {
    empty = Optional.of(id);
  }

  void failGrouping() {
    groupingFailed = true;
  }

  void recover() {
    failed = Optional.empty();
    empty = Optional.empty();
    groupingFailed = false;
    omittedCarrier = Optional.empty();
    wrongDocument = false;
    wrongSource = false;
    consumeInput = false;
    omitAllBundles = false;
  }

  void onNextProject(Runnable action) {
    next = action;
  }

  @Override
  public String kind() {
    return delegate.kind();
  }

  @Override
  public Map<String, List<RawItem>> groupIntoDocuments(List<RawItem> items) {
    if (omitAllBundles) return Map.of();
    if (groupingFailed) throw new IllegalStateException("injected grouping failure");
    if (consumeInput)
      items.removeIf(item -> omittedCarrier.filter(item.externalId()::equals).isPresent());
    Map<String, List<RawItem>> result = new TreeMap<>();
    delegate
        .groupIntoDocuments(items)
        .forEach(
            (id, bundle) ->
                result.put(
                    id,
                    bundle.stream()
                        .filter(
                            item -> !omittedCarrier.filter(item.externalId()::equals).isPresent())
                        .toList()));
    return result;
  }

  @Override
  public Optional<SourceDocument> project(
      String source, String id, List<RawItem> items, ContentReader reader) {
    Runnable action = next;
    next = () -> {};
    action.run();
    if (failed.filter(id::equals).isPresent())
      throw new IllegalStateException("injected render failure");
    if (empty.filter(id::equals).isPresent()) return Optional.empty();
    return delegate
        .project(source, id, items, reader)
        .map(
            doc ->
                new SourceDocument(
                    wrongSource ? "other-source" : doc.sourceId(),
                    wrongDocument ? "other-document" : doc.documentId(),
                    doc.uri(),
                    doc.title(),
                    doc.body(),
                    doc.createdAt(),
                    doc.updatedAt()));
  }
}
