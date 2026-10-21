package io.scos.engine;

/** 묶음의 유닛 한 개. rankScore는 조립 랭커의 점수다. */
public record BundleItem(
    String unitId, String documentId, String title, String content, String uri, double rankScore) {}
