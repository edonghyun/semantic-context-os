package io.scos.boardarchive.profile;

import java.util.*;

public record Selectors(
    String listItem,
    String link,
    String title,
    String date,
    Optional<String> pinned,
    Optional<String> nextPage,
    String body,
    Optional<String> attachments,
    Optional<String> author,
    List<String> identitySelectors,
    List<String> identityFields) {
  public Selectors {
    identitySelectors = List.copyOf(identitySelectors);
    identityFields = List.copyOf(identityFields);
  }

  public Selectors(
      String listItem,
      String link,
      String title,
      String date,
      Optional<String> pinned,
      Optional<String> nextPage,
      String body,
      Optional<String> attachments) {
    this(
        listItem,
        link,
        title,
        date,
        pinned,
        nextPage,
        body,
        attachments,
        Optional.empty(),
        List.of(),
        List.of());
  }
}
