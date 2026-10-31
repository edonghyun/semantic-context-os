package io.scos.engine;

/** 수집에서 격리한 문서 하나. 원인 예외는 싣지 않고 uri·단계·사유만 남긴다. */
public record IngestFailure(String uri, String stage, String message) {}
