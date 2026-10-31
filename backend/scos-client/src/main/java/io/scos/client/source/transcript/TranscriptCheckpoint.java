package io.scos.client.source.transcript;

import io.scos.spi.connector.Checkpoint;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * 파일별 읽기 위치. 키는 "<dir>/<session>.jsonl". 형식: {"files":{키:{"offset":N,"lines":L,"head":sha256}}}.
 */
record TranscriptCheckpoint(Map<String, FileState> files) {
  static final int SCHEMA_VERSION = 1;

  /** offset = 마지막 {@code \n} 바로 뒤 바이트, lines = 읽은 줄 수, head = 첫 줄의 sha256. */
  record FileState(long offset, long lines, String head) {
    FileState(long offset, long lines, @Nullable String head) {
      this.offset = offset;
      this.lines = lines;
      this.head = head == null ? "" : head;
    }
  }

  TranscriptCheckpoint(@Nullable Map<String, FileState> files) {
    this.files =
        Collections.unmodifiableMap(files == null ? new TreeMap<>() : new TreeMap<>(files));
  }

  static TranscriptCheckpoint empty() {
    return new TranscriptCheckpoint(Map.of());
  }

  static TranscriptCheckpoint read(ObjectMapper json, Optional<Checkpoint> checkpoint) {
    if (checkpoint.isEmpty()) return empty();
    // 기존 저장 상태는 누락되거나 null인 숫자를 0으로 읽는다.
    return json.readerFor(TranscriptCheckpoint.class)
        .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .readValue(checkpoint.get().valueJson());
  }

  TranscriptCheckpoint with(String key, FileState state) {
    Map<String, FileState> next = new TreeMap<>(files);
    next.put(key, state);
    return new TranscriptCheckpoint(next);
  }

  Checkpoint toCheckpoint(ObjectMapper json) {
    return new Checkpoint(SCHEMA_VERSION, json.writeValueAsString(this));
  }
}
