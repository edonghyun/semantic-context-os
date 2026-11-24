package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroProtocolException.invalid;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 저장된 노트 원본 하나의 접근 관측(Tiro 접근 설계 §5.1·§5.4): `note.collaborators[].email`마다 소문자 user 키, 전원 공개 노트면
 * 조직 도메인마다 domain 키. 상태는 키가 없어도 OK다(원천이 그렇게 말한 것). 참석자는 권한이 아니라 읽지 않는다. 키를 낸 협업자마다 신원 하나를 낸다 — 허용
 * 도메인 이메일만 검증된 것으로 본다(Tiro는 검증 여부를 주지 않는다).
 */
final class TiroNoteAccess {
  /** 노트 하나의 ACL과 그 협업자 신원. */
  record Observation(RawAcl.Observed acl, List<SourceIdentityRecord> identities) {
    Observation {
      identities = List.copyOf(identities);
    }
  }

  private final SourceInstanceId source;
  private final DomainPolicy domains;
  private final ObjectMapper json;

  TiroNoteAccess(SourceInstanceId source, DomainPolicy domains, ObjectMapper json) {
    this.source = source;
    this.domains = domains;
    this.json = json;
  }

  /** storedContent: `tiro.note` 원본 JSON. JSON이 아니거나 최상위·note가 객체가 아니면 프로토콜 위반이다. */
  Observation observe(String storedContent, boolean everyone, Instant at) {
    Set<AccessKey> keys = new HashSet<>();
    List<SourceIdentityRecord> identities = new ArrayList<>();
    for (String email : collaboratorEmails(note(storedContent))) {
      keys.add(new AccessKey(AccessKey.Kind.USER, source, email));
      identities.add(
          new SourceIdentityRecord(
              email, Optional.of(email), domains.admits(email, source), false, false, at));
    }
    if (everyone)
      for (String domain : domains.orgDomains())
        keys.add(new AccessKey(AccessKey.Kind.DOMAIN, source, domain));
    return new Observation(RawAcl.observed(DocumentAcl.Status.OK, keys, at), identities);
  }

  private JsonNode note(String storedContent) {
    try {
      return TiroJson.object(TiroJson.object(json.readTree(storedContent)).get("note"));
    } catch (JacksonException e) {
      throw invalid("Invalid stored Tiro note");
    }
  }

  /** 쓸 수 있는 협업자 이메일(소문자, 처음 나온 순서, 중복 없음). 협업자 목록이 없거나 배열이 아니면 없다. */
  private static Set<String> collaboratorEmails(JsonNode note) {
    Set<String> emails = new LinkedHashSet<>();
    JsonNode collaborators = note.get("collaborators");
    if (collaborators != null && collaborators.isArray())
      for (JsonNode collaborator : collaborators) {
        JsonNode email = collaborator.get("email");
        if (email != null && email.isString() && isAddress(email.asString()))
          emails.add(email.asString().toLowerCase(Locale.ROOT));
      }
    return emails;
  }

  /** `@`가 정확히 하나이고 양쪽이 비어 있지 않다. */
  private static boolean isAddress(String email) {
    int separator = email.indexOf('@');
    return separator > 0 && separator == email.lastIndexOf('@') && separator < email.length() - 1;
  }
}
