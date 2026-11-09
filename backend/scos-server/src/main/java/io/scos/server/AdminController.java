package io.scos.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.scos.engine.admin.AdminDiagnostics.DocumentAccessView;
import io.scos.engine.admin.AdminDiagnostics.SourceAccessView;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.IssuedToken;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** HTTP 인자와 응답만 변환한다. 현재 역할·변경·감사는 관리 포트가 소유한다. */
@RequestMapping("/admin/v1")
@Profile("daemon")
@ConditionalOnProperty(prefix = "scos.mcp.http", name = "enabled", havingValue = "true")
@RestController
final class AdminController {
  private final AdminPort admin;

  AdminController(AdminPort admin) {
    this.admin = admin;
  }

  @PostMapping("/users")
  ResponseEntity<UserAccount> createUser(HttpServletRequest request, @RequestBody Input body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(admin.createUser(actor(request), text(body, "email"), role(body)));
  }

  @PutMapping("/users/{id}/active")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void active(HttpServletRequest request, @PathVariable String id, @RequestBody Input body) {
    Principal principal = actor(request);
    if (!body.value().path("active").isBoolean()) throw new AdminPort.Failure("invalid_request");
    if (body.value().path("active").asBoolean()) admin.activateUser(principal, id);
    else admin.deactivateUser(principal, id);
  }

  @PutMapping("/users/{id}/role")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void role(HttpServletRequest request, @PathVariable String id, @RequestBody Input body) {
    admin.changeRole(actor(request), id, role(body));
  }

  @GetMapping("/users")
  Page<UserAccount> users(
      HttpServletRequest request,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam Optional<String> after) {
    return admin.users(actor(request), new PageRequest(limit, after));
  }

  @PostMapping("/users/{id}/tokens")
  ResponseEntity<IssuedToken> issueToken(
      HttpServletRequest request, @PathVariable String id, @RequestBody Input body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(admin.issueToken(actor(request), id, text(body, "label")));
  }

  @GetMapping("/users/{id}/tokens")
  Page<TokenMetadata> tokens(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam Optional<String> after) {
    return admin.tokens(actor(request), id, new PageRequest(limit, after));
  }

  @DeleteMapping("/tokens/{tokenId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revokeToken(HttpServletRequest request, @PathVariable UUID tokenId) {
    admin.revokeToken(actor(request), tokenId);
  }

  @GetMapping("/identities")
  Page<IdentityLink> identities(
      HttpServletRequest request,
      @RequestParam Optional<String> userId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam Optional<String> after) {
    return admin.links(actor(request), userId, new PageRequest(limit, after));
  }

  @PutMapping("/identities/{source}/{sourceUserId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void link(
      HttpServletRequest request,
      @PathVariable String source,
      @PathVariable String sourceUserId,
      @RequestBody Input body) {
    admin.linkIdentity(
        actor(request), text(body, "userId"), new SourceInstanceId(source), sourceUserId);
  }

  @DeleteMapping("/identities/{source}/{sourceUserId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void unlink(
      HttpServletRequest request, @PathVariable String source, @PathVariable String sourceUserId) {
    admin.unlinkIdentity(actor(request), new SourceInstanceId(source), sourceUserId);
  }

  @GetMapping("/scopes")
  List<ScopeView> scopes(HttpServletRequest request) {
    return admin.scopes(actor(request)).stream().map(ScopeView::of).toList();
  }

  @PutMapping("/scopes/{source}")
  ScopeView designate(HttpServletRequest request, @PathVariable String source) {
    return ScopeView.of(admin.designateScope(actor(request), new SourceInstanceId(source)));
  }

  @DeleteMapping("/scopes/{source}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revokeScope(HttpServletRequest request, @PathVariable String source) {
    admin.revokeScope(actor(request), new SourceInstanceId(source));
  }

  @GetMapping("/audit")
  Page<AuditEvent> audit(
      HttpServletRequest request,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam Optional<String> after,
      @RequestParam Optional<String> action,
      @RequestParam("actor") Optional<String> actorId,
      @RequestParam Optional<String> targetType,
      @RequestParam Optional<String> targetId) {
    return admin.audit(
        actor(request), new AuditQuery(limit, after, action, actorId, targetType, targetId));
  }

  @GetMapping("/sources/access")
  List<SourceAccessView> sourceAccess(HttpServletRequest request) {
    return admin.sourceAccess(actor(request));
  }

  @GetMapping("/sources/access/explain")
  DocumentAccessView explain(
      HttpServletRequest request,
      @RequestParam String documentId,
      @RequestParam Optional<String> userId) {
    return admin
        .explainAccess(actor(request), documentId, userId)
        .orElseThrow(() -> new AdminPort.Failure("not_found"));
  }

  private Principal actor(HttpServletRequest request) {
    if (request.getAttribute(PrincipalBearerFilter.PRINCIPAL_ATTRIBUTE)
        instanceof Principal principal) return principal;
    throw new AdminPort.Failure("unauthorized");
  }

  private String text(Input body, String field) {
    if (!body.value().path(field).isString()) throw new AdminPort.Failure("invalid_request");
    return body.value().path(field).asString();
  }

  private Role role(Input body) {
    return Role.valueOf(text(body, "role").toUpperCase(Locale.ROOT));
  }

  /** JSON은 경계 안에서만 해석하고 프레임워크의 요청 DEBUG 로그에는 원문을 내주지 않는다. */
  record Input(JsonNode value) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    Input {}

    @Override
    public String toString() {
      return "AdminInput[redacted]";
    }
  }

  record ScopeView(
      SourceInstanceId source,
      String designatedBy,
      Instant designatedAt,
      String visibilityWarning) {
    static ScopeView of(ScopeDesignation scope) {
      return new ScopeView(
          scope.source(), scope.designatedBy(), scope.designatedAt(), scope.visibilityWarning());
    }
  }
}
