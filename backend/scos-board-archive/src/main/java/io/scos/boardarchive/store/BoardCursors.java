package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.PostKey;
import java.util.Optional;

public record BoardCursors(
    Optional<PostKey> front, Optional<BackfillCursor> back, boolean backfillDone) {}
