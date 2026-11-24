package io.scos.boardarchive.driver;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

public record ChangeSignal(Optional<Instant> updatedAt, OptionalInt commentCount) {}
