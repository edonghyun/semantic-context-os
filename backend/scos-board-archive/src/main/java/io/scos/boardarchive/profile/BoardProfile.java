package io.scos.boardarchive.profile;

import java.util.List;

/** postUrl uses {postId}; a missing post must be addressable without a prior list response. */
public record BoardProfile(
    String id,
    String name,
    String category,
    String listUrl,
    String postUrl,
    List<String> datePatterns) {
  public BoardProfile {
    if (id.isBlank() || listUrl.isBlank() || !postUrl.contains("{postId}"))
      throw new IllegalArgumentException("Board needs id, listUrl and postUrl template");
    datePatterns = List.copyOf(datePatterns);
  }
}
