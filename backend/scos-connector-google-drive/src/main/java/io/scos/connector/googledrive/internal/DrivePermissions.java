package io.scos.connector.googledrive.internal;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.AccessKeyInstances;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** 파일 자신의 유효 권한 응답만 해석한다. Directory 없는 그룹 키는 구성원을 합성하지 않는다. */
final class DrivePermissions {
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s:]+@[^@\\s:]+$");
  private static final Set<String> READ_ROLES =
      Set.of("owner", "organizer", "fileOrganizer", "writer", "commenter", "reader");

  enum Type {
    USER,
    GROUP,
    DOMAIN,
    ANYONE
  }

  record Permission(
      String id,
      Type type,
      String role,
      String value,
      boolean deleted,
      Optional<Instant> expiresAt) {
    boolean effectiveAt(Instant at) {
      return !deleted && expiresAt.filter(expiry -> !expiry.isAfter(at)).isEmpty();
    }
  }

  record Page(List<Permission> permissions, Optional<String> next) {
    Page {
      permissions = List.copyOf(permissions);
    }
  }

  Page read(JsonNode response) {
    JsonNode permissions = response.get("permissions");
    if (permissions == null || !permissions.isArray()) throw invalid();
    List<Permission> result = new ArrayList<>();
    for (JsonNode permission : permissions) result.add(permission(permission));
    JsonNode next = response.get("nextPageToken");
    if (next == null) return new Page(result, Optional.empty());
    if (!next.isString()) throw invalid();
    return new Page(
        result, next.asString().isEmpty() ? Optional.empty() : Optional.of(next.asString()));
  }

  private Permission permission(JsonNode value) {
    if (!value.isObject()) throw invalid();
    String id = requiredText(value, "id");
    String role = requiredText(value, "role");
    if (!READ_ROLES.contains(role)) throw invalid();
    Type type =
        switch (requiredText(value, "type")) {
          case "user" -> Type.USER;
          case "group" -> Type.GROUP;
          case "domain" -> Type.DOMAIN;
          case "anyone" -> Type.ANYONE;
          default -> throw invalid();
        };
    JsonNode deleted = value.get("deleted");
    if (deleted != null && !deleted.isBoolean()) throw invalid();
    boolean removed = deleted != null && deleted.asBoolean();
    String subject = removed ? "" : subject(type, value);
    return new Permission(id, type, role, subject, removed, expiration(value));
  }

  private String subject(Type type, JsonNode value) {
    return switch (type) {
      case USER, GROUP -> {
        String email = requiredText(value, "emailAddress").toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) throw invalid();
        yield email;
      }
      case DOMAIN -> requiredText(value, "domain").toLowerCase(Locale.ROOT);
      case ANYONE -> "";
    };
  }

  private Optional<Instant> expiration(JsonNode value) {
    if (!value.has("expirationTime")) return Optional.empty();
    try {
      return Optional.of(Instant.parse(requiredText(value, "expirationTime")));
    } catch (DateTimeParseException error) {
      throw invalid();
    }
  }

  RawAcl map(
      SourceDescriptor source,
      String externalId,
      List<Permission> permissions,
      GoogleDriveConfiguration.PermissionVisibility visibility,
      DomainPolicy domains,
      Instant at) {
    if (visibility != GoogleDriveConfiguration.PermissionVisibility.VERIFIED_FULL
        || !source.kind().equals("google-drive")
        || !new AccessKeyInstances().admits(source.id())
        || !externalId.matches("(?:file|drive):[A-Za-z0-9_-]+"))
      return RawAcl.observed(DocumentAcl.Status.FAILED, Set.of(), at);
    Set<AccessKey> keys = new HashSet<>();
    for (Permission permission : permissions) {
      if (!permission.effectiveAt(at)) continue;
      switch (permission.type()) {
        case USER -> keys.add(new AccessKey(AccessKey.Kind.USER, source.id(), permission.value()));
        case GROUP ->
            keys.add(new AccessKey(AccessKey.Kind.GROUP, source.id(), permission.value()));
        case DOMAIN -> {
          if (domains.admits("member@" + permission.value(), source.id()))
            keys.add(new AccessKey(AccessKey.Kind.DOMAIN, source.id(), permission.value()));
        }
        case ANYONE -> {}
      }
    }
    return RawAcl.observed(DocumentAcl.Status.OK, keys, at);
  }

  List<SourceIdentityRecord> identities(
      SourceDescriptor source, List<Permission> permissions, DomainPolicy domains, Instant at) {
    return permissions.stream()
        .filter(permission -> permission.type() == Type.USER && permission.effectiveAt(at))
        .map(
            permission ->
                new SourceIdentityRecord(
                    permission.value(),
                    Optional.of(permission.value()),
                    domains.admits(permission.value(), source.id()),
                    false,
                    false,
                    at))
        .distinct()
        .toList();
  }

  private static String requiredText(JsonNode value, String field) {
    JsonNode text = value.get(field);
    if (text == null || !text.isString() || text.asString().isBlank()) throw invalid();
    return text.asString();
  }

  private static CallFailureException invalid() {
    return new CallFailureException(
        "google-drive.permissions.list",
        CallFailure.of(
            CallFailure.Kind.INVALID_PROVIDER_RESPONSE, "Invalid Drive permissions response"));
  }
}
