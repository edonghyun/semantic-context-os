package io.scos.store.memory;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.UserTokens;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.util.Map;

/** 관리·신원·인증·확정 관측이 하나의 상태를 보는 조립. */
public final class MemoryAdminScenario {
  private final MemoryIdentityStore identities = new MemoryIdentityStore();
  private final MemoryUserTokens tokens = new MemoryUserTokens(identities);
  private final MemorySourceAccessDirectory directory = new MemorySourceAccessDirectory(identities);
  private final AdminStore store = new MemoryAdminStore(identities, tokens, directory);

  public AdminStore store() {
    return store;
  }

  public void registerSource(SourceAccount source) {
    identities.registerSource(source);
  }

  public UserTokens authentication() {
    return tokens;
  }

  public void seed(AccessDirectory directory) {
    new MemoryIdentitySeed(identities).seed(directory);
  }

  public void observeIdentity(SourceInstanceId source, SourceIdentityRecord identity) {
    directory.save(source, new Snapshot(Map.of(identity.sourceUserId(), identity), Map.of()));
  }

  public IdentityStore identityReader() {
    return identities;
  }
}
