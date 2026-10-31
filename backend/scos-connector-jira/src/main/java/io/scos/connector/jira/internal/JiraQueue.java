package io.scos.connector.jira.internal;

import io.scos.engine.support.call.WorkQueue;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.Checkpoint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira 작업 큐와 그 체크포인트(v1: `{siteUrl, pending, downloadedAttachments}`). 앞에서 배치를 꺼내고, 배치가 끝나면 그 자리에
 * 다음 작업을 넣는다. 이미 내려받은 첨부는 다시 큐에 넣지 않는다.
 */
final class JiraQueue {
  static final int MAX_CONCURRENT_READS = 4;

  private final ObjectMapper json;
  private final String siteUrl;
  private WorkQueue<JiraWork> pending;
  private final Set<String> downloaded;

  private JiraQueue(
      ObjectMapper json, String siteUrl, List<JiraWork> pending, Set<String> downloaded) {
    this.json = json;
    this.siteUrl = siteUrl;
    this.pending = WorkQueue.of(pending);
    this.downloaded = downloaded;
  }

  /** 체크포인트가 없거나 큐가 비었으면 루트부터. 다른 사이트·형식 위반은 요청 없이 실패한다. */
  static JiraQueue resume(ObjectMapper json, Optional<Checkpoint> stored, String siteUrl) {
    List<JiraWork> pending = new ArrayList<>();
    Set<String> downloaded = new LinkedHashSet<>();
    if (stored.isEmpty()) {
      roots(json, pending);
      return new JiraQueue(json, siteUrl, pending, downloaded);
    }
    JsonNode value = JiraPages.object(json.readTree(stored.get().valueJson()));
    JsonNode site = value.get("siteUrl");
    JsonNode list = value.get("pending");
    if (stored.get().schemaVersion() != 1
        || site == null
        || !site.isString()
        || !site.asString().equals(siteUrl)
        || list == null
        || !list.isArray()) throw new JiraProtocolException("Invalid Jira checkpoint");
    JsonNode history = value.get("downloadedAttachments");
    if (history != null) {
      if (!history.isArray()) throw new JiraProtocolException("Invalid Jira attachment checkpoint");
      for (JsonNode id : history)
        if (!id.isString()) throw new JiraProtocolException("Invalid Jira attachment checkpoint");
    }
    for (JsonNode entry : list) pending.add(JiraWork.parse(entry));
    if (pending.isEmpty()) roots(json, pending);
    if (history != null) history.forEach(id -> downloaded.add(id.asString()));
    return new JiraQueue(json, siteUrl, pending, downloaded);
  }

  private static void roots(ObjectMapper json, List<JiraWork> pending) {
    for (WorkKind kind : WorkKind.roots()) pending.add(JiraWork.of(json, kind));
  }

  boolean isEmpty() {
    return pending.isEmpty();
  }

  /** 앞의 배치: 이슈·첨부는 같은 종류가 이어지는 동안 {@value #MAX_CONCURRENT_READS}개까지, 그 밖은 하나. */
  List<JiraWork> nextBatch() {
    JiraWork first = pending.head();
    WorkKind kind = first.kind();
    if (!kind.batched()) return List.of(first);
    List<JiraWork> batch = new ArrayList<>();
    for (JiraWork work : pending.items()) {
      if (work.kind() != kind || batch.size() == MAX_CONCURRENT_READS) break;
      batch.add(work);
    }
    return batch;
  }

  /** 이 첨부를 내려받았다. */
  void downloaded(JiraWork attachment) {
    downloaded.add(attachment.id().orElseThrow());
  }

  /** 아직 내려받지 않은 첨부이거나 첨부가 아니면 큐에 넣을 작업이다. */
  boolean worthQueueing(JiraWork work) {
    return work.kind() != WorkKind.ATTACHMENT || !downloaded.contains(work.id().orElseThrow());
  }

  /** 앞의 배치를 끝냈다: 그 자리에 다음 작업을 넣는다. */
  void advance(int batchSize, List<JiraWork> next) {
    pending = pending.afterHead(batchSize, next, List.of());
  }

  Checkpoint checkpoint() {
    ObjectNode value = json.createObjectNode().put("siteUrl", siteUrl);
    ArrayNode queue = value.putArray("pending");
    pending.items().forEach(work -> queue.add(work.json()));
    ArrayNode history = value.putArray("downloadedAttachments");
    downloaded.forEach(history::add);
    return new Checkpoint(1, JsJson.stringify(value));
  }
}
