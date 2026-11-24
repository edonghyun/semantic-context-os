package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.ChangeSignal;
import java.time.Instant;
import java.util.Optional;

public record PostState(
    String site,
    String postId,
    String boardId,
    PostStatus status,
    Optional<ChangeSignal> lastSignal,
    Optional<Instant> lastFetchedAt,
    Optional<String> projectedHash) {}
