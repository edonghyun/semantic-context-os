package io.scos.client.source.transcript;

import io.scos.client.ClientJson;
import io.scos.client.ContentHash;
import io.scos.client.source.ClaudeProjects;
import io.scos.client.source.Rejections;
import io.scos.client.source.transcript.TranscriptCheckpoint.FileState;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Claude Code 트랜스크립트(`<projectsRoot>/<dir>/<session>.jsonl`) 원천. 줄 하나가 원본 하나다. 바이트 오프셋 커서로 새로 붙은
 * 완전한 줄(`\n`로 끝남)만 읽고, 파일이 줄거나 첫 줄이 바뀌면 처음부터 다시 읽는다. 파일이 사라져도 삭제로 보지 않는다(항상 증분).
 */
public final class TranscriptAdapter implements SourceAdapter {
  public static final String KIND = "claude-transcript";
  public static final String TURN = "claude.transcript.turn";

  private final ContentHash hash = new ContentHash();
  private final ClaudeProjects projects;
  private final Clock clock;

  public TranscriptAdapter(Path projectsRoot, Clock clock) {
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
    Run run =
        new Run(input, sink, TranscriptCheckpoint.read(ClientJson.MAPPER, input.checkpoint()));
    for (Path file : projects.sessionFiles()) {
      if (input.cancel().isCancelled()) return;
      run.collect(file);
    }
  }

  /** collect 한 번의 파일 흐름과 마지막으로 방출한 체크포인트. */
  private final class Run {
    private final Instant now = clock.instant();
    private final PageBuffer pages;
    private final TranscriptLineCapture capture;
    private TranscriptCheckpoint state;
    private String key = "";
    private String documentKey = "";

    Run(CollectionInput input, PageSink sink, TranscriptCheckpoint state) {
      this.pages = new PageBuffer(sink);
      this.capture = new TranscriptLineCapture(input.content(), now);
      this.state = state;
    }

    void collect(Path file) {
      key = projects.key(file);
      documentKey = key.substring(0, key.length() - ".jsonl".length());
      FileState previous = state.files().getOrDefault(key, new FileState(0, 0, ""));
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
        boolean hadState = previous.offset() > 0;
        boolean resume = hadState && canResume(channel, previous);
        FileState start = resume ? previous : new FileState(0, 0, "");
        if (hadState && !resume) addDeletesFrom(countLines(channel), previous.lines());
        pages.flush(checkpointAt(readLines(channel, start)));
      } catch (NoSuchFileException e) {
        // 사라진 파일은 삭제로 보지 않는다.
      } catch (JsonlReader.ReadFailure e) {
        skipUnreadable();
      } catch (IOException e) {
        skipUnreadable();
      }
    }

    /** 아직 안 내보낸 변경을 버리고 마지막으로 방출한 위치부터 다음 run이 재시도하게 한다. */
    private void skipUnreadable() {
      pages.discard();
      pages.add(
          new Rejections(now)
              .of(RejectedItem.FailureKind.INVALID_ITEM, "읽을 수 없는 트랜스크립트 파일", documentKey));
      pages.flush(state.toCheckpoint(ClientJson.MAPPER));
    }

    private FileState readLines(FileChannel channel, FileState start) throws IOException {
      JsonlReader reader = new JsonlReader(channel, start.offset(), start.lines());
      FileState boundary = start;
      while (reader.hasNext()) {
        JsonlReader.Line line = reader.next();
        String head = line.number() == 0 ? hash.sha256(line.bytes()) : boundary.head();
        boundary = new FileState(line.endOffset(), line.number() + 1, head);
        pages.add(capture.capture(line, documentKey));
        FileState completed = boundary;
        pages.flushIfFull(() -> checkpointAt(completed));
      }
      return boundary;
    }

    private Checkpoint checkpointAt(FileState boundary) {
      state = state.with(key, boundary);
      return state.toCheckpoint(ClientJson.MAPPER);
    }

    private void addDeletesFrom(long from, long to) {
      LongStream.range(from, to)
          .forEach(
              i ->
                  pages.add(
                      new RawMutation.Delete(
                          documentKey + "#" + i,
                          Optional.empty(),
                          Optional.empty(),
                          Optional.empty(),
                          now)));
    }
  }

  /** 파일이 커서보다 짧지 않고 첫 줄이 그대로면 커서에서 이어 읽는다. */
  private boolean canResume(FileChannel channel, FileState previous) throws IOException {
    return channel.size() >= previous.offset() && previous.head().equals(firstLineHash(channel));
  }

  private static long countLines(FileChannel channel) throws IOException {
    JsonlReader reader = new JsonlReader(channel, 0, 0);
    long count = 0;
    while (reader.hasNext()) {
      reader.next();
      count++;
    }
    return count;
  }

  private String firstLineHash(FileChannel channel) throws IOException {
    JsonlReader reader = new JsonlReader(channel, 0, 0);
    return reader.hasNext() ? hash.sha256(reader.next().bytes()) : "";
  }
}
