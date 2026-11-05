package io.scos.connector.slack.internal;

import io.scos.engine.support.call.CursorTrail;
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

/** 본문과 독립적으로 참여 채널·전체 멤버·워크스페이스 신원을 관측한다. 정상 반환만 전체 디렉터리 완료를 뜻한다. */
public final class SlackAccessCollection {
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
  public RawAcl aclFromExternalId(SourceDescriptor source, String externalId, Instant at) {
    String[] parts = externalId.split(":", -1);
    if (parts.length != 2
        || !parts[0].matches("[CDG][A-Z0-9]+")
        || SlackTs.parse(parts[1]).isEmpty())
      return RawAcl.observed(DocumentAcl.Status.FAILED, Set.of(), at);
    return SlackMessage.aclForChannel(source.id(), parts[0], at);
  }

  /** sink 예외·취소는 즉시 전파하며, 채널 실패는 빈 그룹으로 좁힌 뒤 다른 채널을 관측하고 예외로 마친다. */
  public void collect(AccessInput input, AclSink sink) {
    if (input.cancel().isCancelled())
      throw new CallFailureException(
          "slack.access", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
    Map<String, RawAcl> acls = new LinkedHashMap<>();
    for (String id : input.externalIds())
      acls.put(id, aclFromExternalId(input.source(), id, clock.instant()));
    sink.accept(new AclPage(acls, List.of(), List.of()));
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    new Walk(input, sink, token).collect();
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

    Walk(AccessInput input, AclSink sink, String token) {
      this.input = input;
      this.sink = sink;
      this.token = token;
    }

    void collect() {
      String team = call("auth.test", timeout -> client.teamId(token, timeout));
      List<CallFailureException> failures = new ArrayList<>();
      for (String channel : channels()) {
        GroupSnapshot snapshot;
        try {
          snapshot = channel(channel);
        } catch (CallFailureException failure) {
          if (failure.failure().kind() == CallFailure.Kind.CANCELLED) throw failure;
          failures.add(failure);
          snapshot = new GroupSnapshot(channel, Set.of(), clock.instant());
        }
        sink.accept(new AclPage(Map.of(), List.of(snapshot), List.of()));
      }
      identities(team);
      if (!failures.isEmpty()) throw failures.getFirst();
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
          .execute("slack." + method, call, SlackAccessCollection::classify, input.cancel());
    }

    private void advancing(CursorTrail trail, String list, Optional<String> next) {
      if (trail.revisits(next)) throw SlackApiException.cursorCycle(trail.cycle(list, next));
    }
  }
}
