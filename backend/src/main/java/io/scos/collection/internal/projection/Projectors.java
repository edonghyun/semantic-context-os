package io.scos.collection.internal.projection;

import io.scos.spi.projection.Projector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** kind → 투영기 레지스트리(ADR-0022). 같은 kind를 두 투영기가 맡으면 조립 시점에 거부한다. */
public final class Projectors {
  private final Map<String, Projector> byKind;

  private Projectors(Map<String, Projector> byKind) {
    this.byKind = byKind;
  }

  public static Projectors of(List<Projector> projectors) {
    Map<String, Projector> byKind = new LinkedHashMap<>();
    for (Projector p : projectors) {
      Projector previous = byKind.putIfAbsent(p.kind(), p);
      if (previous != null)
        throw new IllegalArgumentException("projector registered twice: " + p.kind());
    }
    return new Projectors(Map.copyOf(byKind));
  }

  public Optional<Projector> forKind(String kind) {
    return Optional.ofNullable(byKind.get(kind));
  }
}
