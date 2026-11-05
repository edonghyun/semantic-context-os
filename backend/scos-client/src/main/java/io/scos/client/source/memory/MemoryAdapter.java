package io.scos.client.source.memory;

import io.scos.client.ClientJson;
import io.scos.client.ContentHash;
import io.scos.client.DocumentMetadata;
import io.scos.client.redact.Redaction;
import io.scos.client.source.ClaudeProjects;
import io.scos.client.source.Rejections;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude Code 자동 메모리(`<projectsRoot>/<dir>/memory/*.md`) 원천. 파일 하나가 원본 하나다. 체크포인트의 sha와 달라진 파일만
 * Upsert로 내고, 체크포인트에 있는데 사라진 파일은 Delete로 낸다. 범위는 증분이다: 스냅숏 정리는 이번 run에 안 낸(바뀌지 않은) 파일까지 지우므로 쓰지 않고,
 * 삭제는 직접 낸다.
 */
public final class MemoryAdapter implements SourceAdapter {
  public static final String KIND = "claude-memory";
  public static final String RESOURCE_TYPE = "claude.memory.file";
  private static final int SCHEMA_VERSION = 1;

  private final ContentHash hash = new ContentHash();
  private final ClaudeProjects projects;
  private final Clock clock;
  private final Redaction redaction = Redaction.standard();
  private final ObjectMapper json = ClientJson.MAPPER;

  public MemoryAdapter(Path projectsRoot, Clock clock) {
    this.projects = new ClaudeProjects(projectsRoot);
    this.clock = clock;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public void validateConfig(SourceConfig config) {}

  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    return CollectionCoverage.INCREMENTAL;
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    Instant now = clock.instant();
    Map<String, FileState> previous = readCheckpoint(input.checkpoint());
    Map<String, FileState> next = new TreeMap<>();
    List<RawMutation> mutations = new ArrayList<>();
    List<RejectedItem> rejections = new ArrayList<>();
    for (Path file : projects.memoryFiles()) {
      if (input.cancel().isCancelled()) return;
      String key = projects.key(file);
      FileState before = previous.get(key);
      Optional<FileState> after =
          switch (readFile(file, input, previous, now)) {
            case Changed changed -> {
              mutations.add(changed.mutation());
              yield Optional.of(changed.state());
            }
            case Unchanged unchanged -> Optional.of(unchanged.state());
            case Rejected rejected -> {
              rejections.add(rejected.item());
              yield Optional.ofNullable(before);
            }
            case Vanished ignored -> Optional.ofNullable(before);
          };
      after.ifPresent(state -> next.put(key, state));
    }
    for (String key : previous.keySet()) {
      if (!next.containsKey(key)) {
        mutations.add(
            new RawMutation.Delete(key, Optional.empty(), Optional.empty(), Optional.empty(), now));
      }
    }
    if (mutations.isEmpty() && rejections.isEmpty()) return;
    sink.accept(new CollectionPage(mutations, rejections, toCheckpoint(next)));
  }

  private FileOutcome readFile(
      Path file, CollectionInput input, Map<String, FileState> previous, Instant now) {
    String key = projects.key(file);
    try {
      byte[] bytes = Files.readAllBytes(file);
      String sha = hash.sha256(bytes);
      FileState before = previous.get(key);
      if (before != null && before.sha().equals(sha)) return new Unchanged(before);
      String text = redaction.apply(new String(bytes, StandardCharsets.UTF_8));
      RawContent content = input.content().json(ClientJson.MEDIA_TYPE, textJson(text));
      Instant modified = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.MILLIS);
      return new Changed(
          upsert(key, text, content, modified, now),
          new FileState(bytes.length, modified.toString(), sha));
    } catch (NoSuchFileException e) {
      return new Vanished();
    } catch (IOException e) {
      return new Rejected(
          new Rejections(now).of(RejectedItem.FailureKind.UNSUPPORTED_ITEM, "읽을 수 없는 메모리 파일", key));
    }
  }

  private sealed interface FileOutcome {}

  private record Changed(RawMutation mutation, FileState state) implements FileOutcome {}

  private record Unchanged(FileState state) implements FileOutcome {}

  private record Rejected(RejectedItem item) implements FileOutcome {}

  private record Vanished() implements FileOutcome {}

  private RawMutation upsert(
      String key, String text, RawContent content, Instant modified, Instant now) {
    String documentId = "memory/" + key;
    return new RawMutation.Upsert(
        new RawItem(
            key,
            RESOURCE_TYPE,
            hash.sha256(text.getBytes(StandardCharsets.UTF_8)),
            Optional.empty(),
            Optional.empty(),
            Optional.of(modified.toString()),
            now,
            DocumentMetadata.of(documentId).toJson(),
            Optional.of(content),
            RawAcl.notObserved()));
  }

  private String textJson(String text) {
    return json.createObjectNode().put("text", text).toString();
  }

  /** 파일 상태: 크기·수정 시각(참고용)·내용 sha256(변경 판단). */
  private record FileState(long size, String mtime, String sha) {
    FileState(long size, @Nullable String mtime, @Nullable String sha) {
      this.size = size;
      this.mtime = mtime == null ? "" : mtime;
      this.sha = sha == null ? "" : sha;
    }
  }

  private record MemoryCheckpoint(Map<String, FileState> files) {
    MemoryCheckpoint(@Nullable Map<String, FileState> files) {
      this.files =
          Collections.unmodifiableMap(files == null ? new TreeMap<>() : new TreeMap<>(files));
    }
  }

  private Map<String, FileState> readCheckpoint(Optional<Checkpoint> checkpoint) {
    return checkpoint
        .map(
            saved -> {
              // 기존 저장 상태는 누락되거나 null인 크기를 0으로 읽는다.
              MemoryCheckpoint state =
                  json.readerFor(MemoryCheckpoint.class)
                      .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                      .readValue(saved.valueJson());
              return state.files();
            })
        .orElse(Map.of());
  }

  private Checkpoint toCheckpoint(Map<String, FileState> files) {
    return new Checkpoint(SCHEMA_VERSION, json.writeValueAsString(new MemoryCheckpoint(files)));
  }
}
