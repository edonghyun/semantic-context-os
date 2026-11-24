package io.scos.boardarchive.projection;

import io.scos.boardarchive.driver.CommentData;
import io.scos.boardarchive.store.*;
import io.scos.spi.projection.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class ArchiveProjection {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ArchiveStore store;
  private final double maxShrink;

  public ArchiveProjection(ArchiveStore store) {
    this(store, 0.20);
  }

  public ArchiveProjection(ArchiveStore store, double maxShrink) {
    if (!Double.isFinite(maxShrink) || maxShrink < 0 || maxShrink > 1)
      throw new IllegalArgumentException("maxShrink must be between 0 and 1");
    this.store = store;
    this.maxShrink = maxShrink;
  }

  public Projection project(String site, Set<String> boardIds, boolean full) {
    List<ProjectionRow> rows = store.projectionRows(site, boardIds);
    guard(site, rows.size(), full);
    List<SourceDocument> documents = new ArrayList<>();
    Set<String> live = new HashSet<>();
    Map<String, String> hashes = new HashMap<>();
    Set<String> postIds = new HashSet<>();
    for (ProjectionRow row : rows) {
      SourceDocument document = document(site, row);
      String hash = hash(document);
      live.add(document.documentId());
      postIds.add(row.postId());
      hashes.put(document.documentId(), hash);
      if (full || !row.projectedHash().equals(Optional.of(hash))) documents.add(document);
    }
    store.retainProjectedHashes(site, postIds);
    store.saveLiveCount(site, live.size());
    return new Projection(documents, live, hashes);
  }

  private void guard(String site, int current, boolean full) {
    OptionalInt previous = store.lastLiveCount(site);
    if (!full
        && previous.isPresent()
        && previous.getAsInt() > 0
        && (previous.getAsInt() - current) / (double) previous.getAsInt() > maxShrink)
      throw new LiveSetShrinkRefusedException(previous.getAsInt(), current);
  }

  private SourceDocument document(String site, ProjectionRow row) {
    StringBuilder body =
        new StringBuilder("[").append(row.boardName()).append("]\n").append(row.text());
    for (CommentData c : row.comments())
      body.append("\n\n— ")
          .append(c.author())
          .append(" (")
          .append(c.createdAt())
          .append(")\n")
          .append(c.text());
    return new SourceDocument(
        site,
        site + ":" + row.postId(),
        row.url(),
        row.title(),
        body.toString(),
        row.createdAt().toString(),
        row.fetchedAt().toString());
  }

  private String hash(SourceDocument document) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public void markProjected(String documentId, String hash) {
    int split = documentId.indexOf(':');
    if (split < 1 || split == documentId.length() - 1)
      throw new IllegalArgumentException("Expected site:postId");
    store.setProjectedHash(
        documentId.substring(0, split), documentId.substring(split + 1), Optional.of(hash));
  }
}
