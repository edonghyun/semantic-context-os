package io.scos.boardarchive.projection;

import io.scos.boardarchive.driver.*;
import io.scos.boardarchive.store.*;
import io.scos.boardarchive.testsupport.*;
import java.nio.file.Path;
import java.util.*;

final class ProjectionScenario implements AutoCloseable {
  final ArchiveScenario archive;
  private final ArchiveProjection projection;

  ProjectionScenario(Path file) {
    archive = new ArchiveScenario(file);
    projection = new ArchiveProjection(archive.store, 0.20);
  }

  Projection project() {
    return project(Set.of("a"), false);
  }

  Projection project(Set<String> boards, boolean full) {
    return projection.project(ArchiveScenario.SITE, boards, full);
  }

  void mark(Projection p) {
    p.documents()
        .forEach(d -> projection.markProjected(d.documentId(), p.hashes().get(d.documentId())));
  }

  void givenPost() {
    archive.givenPost();
  }

  void edit() {
    archive.save(archive.found("Edited"));
  }

  void comment() {
    archive.save(archive.found("Body", "raw", List.of(archive.comment("Reply")), true));
  }

  void renameBoard() {
    archive.store.upsertBoards(
        ArchiveScenario.SITE, List.of(new Board("a", "Renamed", "")), archive.clock.instant());
  }

  void status(PostStatus status) {
    status("42", status);
  }

  void status(String id, PostStatus status) {
    archive.store.markStatus(ArchiveScenario.SITE, id, status);
  }

  void givenPostInOtherBoard() {
    archive.store.storeFound(
        ArchiveScenario.SITE, "b", "43", archive.found("Other"), archive.clock.instant());
  }

  void givenFivePosts() {
    for (String id : List.of("1", "2", "3", "4", "5"))
      archive.store.storeFound(
          ArchiveScenario.SITE, "a", id, archive.found("Body " + id), archive.clock.instant());
  }

  @Override
  public void close() {
    archive.close();
  }
}
