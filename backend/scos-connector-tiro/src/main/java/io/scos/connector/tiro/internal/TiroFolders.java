package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroProtocolException.invalid;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 워크스페이스 폴더 목록의 유효 공개 판정(Tiro 접근 설계 §5.2): 자기와 목록에 있는 모든 조상의 `sharingType`이 전원 공개 값일 때만 공개다. 모르는
 * 값·없는 값은 비공개, `isTeamFolder`는 읽지 않는다. 부모가 목록에 없으면 최상위로 본다. 두 집합의 합은 응답의 모든 폴더 id이고 서로소다.
 */
record TiroFolders(Set<String> publicIds, Set<String> privateIds) {
  private static final Set<String> ALL_MEMBER = Set.of("ALL_MEMBER_VIEWER", "ALL_MEMBER_EDITOR");
  private static final String INVALID = "Invalid Tiro folder listing";

  private record Folder(Optional<String> parentId, boolean allMember) {}

  TiroFolders {
    publicIds = Set.copyOf(publicIds);
    privateIds = Set.copyOf(privateIds);
  }

  /** 폴더 배열을 읽는다. 배열이 아니거나 id 형식이 틀리거나 중복이면, 또는 부모 사슬이 순환하면 프로토콜 위반이다. */
  static TiroFolders read(JsonNode body) {
    if (!body.isArray()) throw invalid(INVALID);
    Map<String, Folder> folders = new LinkedHashMap<>();
    for (JsonNode entry : body) {
      String id = id(TiroJson.object(entry).get("id"));
      JsonNode parent = entry.get("parentId");
      JsonNode sharing = entry.get("sharingType");
      Folder folder =
          new Folder(
              TiroJson.present(parent) ? Optional.of(id(parent)) : Optional.empty(),
              sharing != null && sharing.isString() && ALL_MEMBER.contains(sharing.asString()));
      if (folders.putIfAbsent(id, folder) != null) throw invalid(INVALID);
    }
    Set<String> open = new HashSet<>();
    Set<String> closed = new HashSet<>();
    for (String id : folders.keySet()) (effectivelyPublic(id, folders) ? open : closed).add(id);
    return new TiroFolders(open, closed);
  }

  /** 부모 사슬을 끝(목록에 없는 부모)까지 걷는다. 순환은 공개 여부와 상관없이 위반이다. */
  private static boolean effectivelyPublic(String id, Map<String, Folder> folders) {
    Set<String> seen = new HashSet<>();
    boolean allMember = true;
    Optional<String> at = Optional.of(id);
    while (at.isPresent() && folders.containsKey(at.get())) {
      if (!seen.add(at.get())) throw invalid("Tiro folder tree has a cycle");
      Folder folder = folders.get(at.get());
      allMember &= folder.allMember();
      at = folder.parentId();
    }
    return allMember;
  }

  /** 비어 있지 않은 문자열이거나 long 범위의 정수(문자열로 바꾼다). */
  private static String id(JsonNode value) {
    if (value != null && value.isString() && !value.asString().isEmpty()) return value.asString();
    if (value != null && value.isIntegralNumber() && value.canConvertToLong())
      return Long.toString(value.asLong());
    throw invalid(INVALID);
  }
}
