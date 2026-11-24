package io.scos.boardarchive.driver;

import java.time.Instant;
import java.util.Optional;

public record CommentData(
    String id, Optional<String> parentId, String author, Instant createdAt, String text) {}
