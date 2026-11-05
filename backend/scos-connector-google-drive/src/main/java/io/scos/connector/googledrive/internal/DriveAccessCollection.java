package io.scos.connector.googledrive.internal;

import io.scos.engine.EventLog;
import io.scos.engine.support.call.CursorTrail;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RawAcl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** 파일별 전체 권한을 매 run 다시 읽는다. 대상 실패는 FAILED를 보낸 뒤 나머지를 관측하고 미완료로 끝낸다. */
public final class DriveAccessCollection {
  private static final Logger log = LoggerFactory.getLogger(DriveAccessCollection.class);
  private static final String OPERATION = "google-drive.permissions.list";
  private static final String FIELDS =
      "nextPageToken,permissions(id,type,role,emailAddress,domain,deleted,expirationTime,permissionDetails)";

  private final CredentialResolver credentials;
  private final GoogleDriveApiClient client;
  private final ObjectMapper json;
  private final Clock clock;
  private final DrivePermissions permissions = new DrivePermissions();

  public DriveAccessCollection(
      CredentialResolver credentials, GoogleDriveApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  /** 빈 대상은 정상 완료다. sink 오류·취소·점유 상실은 즉시 전파한다. Directory 미설정은 지원 범위다. */
  public void collect(AccessInput input, AclSink sink) {
    requireActive(input);
    new Attempt(input).collect(sink);
  }

  /** 세션과 비용은 접근 시도 하나가 소유한다. 페이지·재시도마다 실제 permission 호출 직전에 센다. */
  private final class Attempt {
    private final AccessInput input;
    private final GoogleDriveConfiguration config;
    private final GoogleDriveSession session;
    private final List<String> targets;
    private final Instant started = clock.instant();
    private final DriveCalls calls;

    Attempt(AccessInput input) {
      this.input = input;
      config = GoogleDriveConfiguration.read(json, input.source().config());
      session = client.session(credentials.resolve(input.source().credentialRef()));
      calls = new DriveCalls(input.calls(), input.cancel(), session, config, json);
      targets = input.externalIds();
    }

    void collect(AclSink sink) {
      try {
        List<CallFailureException> failures = new ArrayList<>();
        for (String id : targets) {
          requireActive(input);
          sink.accept(observeTarget(id, failures));
        }
        requireActive(input);
        if (!failures.isEmpty()) throw failures.getFirst();
      } finally {
        EventLog.info(log, "collection.drive_access.finished")
            .source(input.source().id())
            .detail(
                "targetCount",
                targets.size(),
                "permissionCalls",
                session.dataRequests(),
                "elapsedMs",
                Duration.between(started, clock.instant()).toMillis())
            .log();
      }
    }

    private AclPage observeTarget(String id, List<CallFailureException> failures) {
      try {
        return observe(id);
      } catch (UnreadableAcl unreadable) {
        return new AclPage(
            Map.of(id, RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, clock.instant())),
            List.of(),
            List.of(),
            Set.of(id));
      } catch (UnverifiedVisibility blocked) {
        failures.add(incomplete("Unverified Drive permission visibility"));
        return new AclPage(
            Map.of(id, RawAcl.failed(AccessFailureReason.VISIBILITY_UNVERIFIED, clock.instant())),
            List.of(),
            List.of());
      } catch (CallFailureException failure) {
        if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
        failures.add(failure.at(json.createObjectNode().put("fileId", id).toString()));
        return new AclPage(Map.of(id, failed(clock.instant())), List.of(), List.of());
      }
    }

    private AclPage observe(String id) {
      if (!id.matches("(?:file|drive):[A-Za-z0-9_-]+"))
        throw incomplete("Invalid Drive access target");
      if (config.permissionVisibility()
          != GoogleDriveConfiguration.PermissionVisibility.VERIFIED_FULL)
        throw new UnverifiedVisibility();
      List<DrivePermissions.Permission> observed = list(id.substring(id.indexOf(':') + 1));
      Instant at = clock.instant();
      RawAcl acl =
          permissions.map(
              input.source(), id, observed, config.permissionVisibility(), input.domains(), at);
      if (acl instanceof RawAcl.Observed observation
          && observation.status() == DocumentAcl.Status.FAILED)
        throw incomplete("Unverified Drive permission visibility");
      var identities = permissions.identities(input.source(), observed, input.domains(), at);
      return new AclPage(Map.of(id, acl), List.of(), identities);
    }

    private List<DrivePermissions.Permission> list(String fileId) {
      Map<String, DrivePermissions.Permission> result = new LinkedHashMap<>();
      CursorTrail trail = new CursorTrail();
      Optional<String> cursor = Optional.empty();
      do {
        trail.request(cursor);
        var page = readPage(fileId, cursor);
        if (trail.revisits(page.next())) throw incomplete("Drive permissions cursor cycle");
        for (var permission : page.permissions()) {
          var previous = result.putIfAbsent(permission.id(), permission);
          if (previous != null && !previous.equals(permission))
            throw incomplete("Conflicting Drive permission observations");
        }
        cursor = page.next();
      } while (cursor.isPresent());
      return List.copyOf(result.values());
    }

    private DrivePermissions.Page readPage(String fileId, Optional<String> cursor) {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("supportsAllDrives", "true");
      query.put("fields", FIELDS);
      cursor.ifPresent(value -> query.put("pageToken", value));
      String path =
          "/drive/v3/files/"
              + GoogleQuery.component(fileId)
              + "/permissions?"
              + GoogleQuery.form(query);
      return permissions.read(calls.json(GoogleService.DRIVE, path, DriveCalls.Availability.ACL));
    }
  }

  private static final class UnverifiedVisibility extends RuntimeException {}

  private static RawAcl failed(Instant at) {
    return RawAcl.observed(DocumentAcl.Status.FAILED, Set.of(), at);
  }

  private static CallFailureException incomplete(String message) {
    return new CallFailureException(
        OPERATION, CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, message));
  }

  private static void requireActive(AccessInput input) {
    if (input.cancel().isCancelled() || Thread.currentThread().isInterrupted())
      throw new CallFailureException(
          OPERATION, CallFailure.of(CallFailure.Kind.CANCELLED, "Drive access cancelled"));
  }
}
