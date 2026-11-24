package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.PostKey;

public record BackfillCursor(String driverCursor, PostKey key) {}
