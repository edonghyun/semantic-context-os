package scosrules;

import io.scos.engine.ContextEngine;
import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.engine.support.PureFunctions;
import io.scos.mcp.wire.Wire;
import io.scos.spi.store.UnitStore;

/**
 * 클라이언트 경계 규칙(io.scos.PublicBoundaryRulesTest R4)이 실제로 잡는지 보는 표본. 패키지가 io.scos 밖이라 본 규칙의 대상이 아니다.
 * 이름이 곧 기대다: Violates…는 규칙에 걸려야 하고 Passes…는 걸리지 않아야 한다.
 */
public final class ClientBoundarySamples {
  private ClientBoundarySamples() {}

  public static final class PassesPublicPackages {
    ContextEngine engine;
    UnitStore store;
    String text;
  }

  public static final class ViolatesEngineSupport {
    PureFunctions functions;
  }

  public static final class ViolatesMcpWire {
    Wire.FreshnessView view;
  }

  public static final class ViolatesEngineInternal {
    DocumentIngestor ingestor;
  }
}
