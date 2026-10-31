package io.scos.engine;

/** 묶음의 유닛 한 개. rankScore는 조립 랭커의 점수, capturedAt은 출처의 수집 시각(ISO 문자열)이다. */
public record BundleItem(
    String unitId,
    String documentId,
    String title,
    String content,
    String uri,
    String capturedAt,
    double rankScore) {}
