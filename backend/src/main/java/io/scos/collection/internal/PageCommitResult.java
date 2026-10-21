package io.scos.collection.internal;

/** 페이지 커밋 결과(TS PageCommitResult). */
public record PageCommitResult(
    long revision, int upserted, int deleted, int unchanged, int rejected) {}
