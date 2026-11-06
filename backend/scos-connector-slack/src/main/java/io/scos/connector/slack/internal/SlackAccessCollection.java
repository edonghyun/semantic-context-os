package io.scos.connector.slack.internal;

import io.scos.engine.EventLog;
import io.scos.engine.support.call.CursorTrail;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 본문과 독립적으로 참여 채널·전체 멤버·워크스페이스 신원을 관측한다. 정상 반환만 전체 디렉터리 완료를 뜻한다. */
public final class SlackAccessCollection {
  // 재관측도 기존 로컬 보충처럼 한 저장 트랜잭션의 carrier 수를 제한한다.
  private static final int ACL_PAGE_SIZE = 500;
  private static final Logger log = LoggerFactory.getLogger(SlackAccessCollection.class);
  private final CredentialResolver credentials;
  private final SlackWebApiClient client;
  private final Clock clock;

  public SlackAccessCollection(
      CredentialResolver credentials, SlackWebApiClient client, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.clock = clock;
  }

  /** 형식이 잘못된 과거 id는 FAILED다. 유효한 메시지의 권한 키는 API 없이 정해진다. */
  private RawAcl aclFromExternalId(SourceDescriptor source, String externalId, Instant at) {
    String[] parts = externalId.split(":", -1);
    if (parts.length != 2
        || !parts[0].matches("[CDG][A-Z0-9]+")
        || SlackTs.parse(parts[1]).isEmpty())
      return RawAcl.observed(DocumentAcl.Status.FAILED, Set.of(), at);
    return SlackMessage.aclForChannel(source.id(), parts[0], at);
  }

  /** sink 예외·취소는 즉시 전파한다. channel_not_found만 해당 채널의 확정 거부이며 다른 실패는 디렉터리 미완료다. */
  public void collect(AccessInput input, AclSink sink) {
    if (input.cancel().isCancelled())
      throw new CallFailureException(
          "slack.access", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    new Walk(input, sink, token).collect();
  }

  private void emitAcls(AclSink sink, Map<String, RawAcl> acls) {
    Map<String, RawAcl> batch = new LinkedHashMap<>();
    for (var entry : acls.entrySet()) {
      batch.put(entry.getKey(), entry.getValue());
      if (batch.size() == ACL_PAGE_SIZE) {
        sink.accept(new AclPage(batch, List.of(), List.of()));
        batch.clear();
      }
    }
    if (!batch.isEmpty()) sink.accept(new AclPage(batch, List.of(), List.of()));
  }

  private static CallFailure classify(Exception error) {
    if (error instanceof SlackTransportException transport
        && (transport.status() == 403 || transport.status() == 404))
      return CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, "Slack channel access denied");
    if (error instanceof SlackApiException api
        && (api.code().equals("not_in_channel") || api.code().equals("channel_not_found")))
      return CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, api.getMessage());
    return SlackErrors.classify(error);
  }

  private final class Walk {
    private final AccessInput input;
    private final AclSink sink;
    private final String token;
    private final Set<String> participants = new LinkedHashSet<>();
    private final Map<String, List<String>> carriers = new LinkedHashMap<>();

    Walk(AccessInput input, AclSink sink, String token) {
      this.input = input;
      this.sink = sink;
      this.token = token;
      for (String id : input.externalIds()) {
        int separator = id.indexOf(':');
        String channel = separator < 0 ? "" : id.substring(0, separator);
        carriers.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(id);
      }
    }

    void collect() {
      String team;
      Set<String> channels;
      try {
        team = call("auth.test", timeout -> client.teamId(token, timeout));
        channels = channels();
      } catch (CallFailureException failure) {
        if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
        emitLocalAcls(carriers.values().stream().flatMap(List::stream).toList());
        throw failure;
      }
      List<CallFailureException> failures = new ArrayList<>();
      for (String channel : channels) {
        GroupSnapshot snapshot;
        try {
          snapshot = channel(channel);
        } catch (UnreadableChannel denied) {
          closeChannel(channel, denied.operation);
          continue;
        } catch (CallFailureException failure) {
          if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
          failures.add(failure);
          snapshot = new GroupSnapshot(channel, Set.of(), clock.instant());
        }
        sink.accept(new AclPage(Map.of(), List.of(snapshot), List.of()));
        emitLocalAcls(takeCarriers(channel));
      }
      // 목록에 없는 채널은 기존 로컬 키를 유지하며, 접근 허용 여부는 디렉터리 병합이 결정한다.
      emitLocalAcls(carriers.values().stream().flatMap(List::stream).toList());
      identities(team);
      if (!failures.isEmpty()) throw failures.getFirst();
    }

    private List<String> takeCarriers(String channel) {
      return Optional.ofNullable(carriers.remove(channel)).orElse(List.of());
    }

    private void emitLocalAcls(List<String> ids) {
      Map<String, RawAcl> acls = new LinkedHashMap<>();
      for (String id : ids) acls.put(id, aclFromExternalId(input.source(), id, clock.instant()));
      emitAcls(sink, acls);
    }

    private void closeChannel(String channel, String operation) {
      Map<String, RawAcl> closed = new LinkedHashMap<>();
      for (String id : takeCarriers(channel))
        closed.put(id, RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, clock.instant()));
      emitAcls(sink, closed);
      sink.accept(
          new AclPage(
              Map.of(),
              List.of(new GroupSnapshot(channel, Set.of(), clock.instant())),
              List.of(),
              Set.of(channel)));
      EventLog.warn(log, "collection.slack_access.channel_unreadable")
          .source(input.source().id())
          .detail(
              "reason",
              "acl_unreadable",
              "operation",
              operation,
              "providerLocator",
              Map.of("channelId", channel))
          .log();
    }

    private Set<String> channels() {
      Set<String> channels = new LinkedHashSet<>();
      CursorTrail trail = new CursorTrail();
      Optional<String> cursor = Optional.empty();
      do {
        Optional<String> current = cursor;
        trail.request(current);
        var page =
            call(
                "users.conversations",
                timeout -> {
                  var result = client.listAccessChannels(token, current, timeout);
                  advancing(trail, "conversations", result.nextCursor());
                  return result;
                });
        channels.addAll(page.channelIds());
        cursor = page.nextCursor();
      } while (cursor.isPresent());
      return channels;
    }

    private GroupSnapshot channel(String id) {
      boolean isPublic =
          call("conversations.info", timeout -> client.isPublicChannel(token, id, timeout));
      Set<String> members = new LinkedHashSet<>();
      CursorTrail trail = new CursorTrail();
      Optional<String> cursor = Optional.empty();
      do {
        Optional<String> current = cursor;
        trail.request(current);
        var page =
            call(
                "conversations.members",
                timeout -> {
                  var result = client.members(token, id, current, timeout);
                  advancing(trail, "members:" + id, result.nextCursor());
                  return result;
                });
        members.addAll(page.members());
        cursor = page.nextCursor();
      } while (cursor.isPresent());
      participants.addAll(members);
      if (isPublic) members.add("~members");
      return new GroupSnapshot(id, members, clock.instant());
    }

    private void identities(String team) {
      Set<String> seen = new HashSet<>();
      Set<String> regular = new HashSet<>();
      CursorTrail trail = new CursorTrail();
      Optional<String> cursor = Optional.empty();
      do {
        Optional<String> current = cursor;
        trail.request(current);
        var page =
            call(
                "users.list",
                timeout -> {
                  var result = client.users(token, team, current, timeout);
                  advancing(trail, "users", result.nextCursor());
                  return result;
                });
        List<SourceIdentityRecord> identities = new ArrayList<>();
        for (var user : page.users()) {
          seen.add(user.id());
          if (!user.guest() && !user.deleted()) regular.add(user.id());
          identities.add(user.observedAt(clock.instant()));
        }
        sink.accept(new AclPage(Map.of(), List.of(), identities));
        cursor = page.nextCursor();
      } while (cursor.isPresent());
      finishIdentities(seen, regular);
    }

    private void finishIdentities(Set<String> seen, Set<String> regular) {
      List<SourceIdentityRecord> missing =
          participants.stream()
              .filter(id -> !seen.contains(id))
              .map(
                  id ->
                      new SourceIdentityRecord(
                          id, Optional.empty(), false, true, false, clock.instant()))
              .toList();
      sink.accept(
          new AclPage(
              Map.of(),
              List.of(GroupSnapshot.synthetic("~members", regular, clock.instant())),
              missing));
    }

    private <T> T call(String method, CallExecutor.Call<T> call) {
      return input
          .calls()
          .execute(
              "slack." + method,
              call,
              error -> {
                if ((method.equals("conversations.info") || method.equals("conversations.members"))
                    && error instanceof SlackApiException api
                    && api.code().equals("channel_not_found"))
                  throw new UnreadableChannel("slack." + method);
                return classify(error);
              },
              input.cancel());
    }

    private void advancing(CursorTrail trail, String list, Optional<String> next) {
      if (trail.revisits(next)) throw SlackApiException.cursorCycle(trail.cycle(list, next));
    }
  }

  /** 실행기가 오류 코드를 지우기 전에 확정 채널 거부를 구분한다. */
  private static final class UnreadableChannel extends RuntimeException {
    private final String operation;

    UnreadableChannel(String operation) {
      this.operation = operation;
    }
  }
}
