package io.scos.spi.connector;

/** 한 응답으로 완전히 확인한 container 또는 페이지 trash 상태. */
public record ReconciliationObservation(
    String fingerprint,
    String resourceId,
    ReconciliationKind kind,
    ReconciliationContext context) {}
