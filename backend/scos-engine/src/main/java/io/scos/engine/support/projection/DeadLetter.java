package io.scos.engine.support.projection;

/** sync에서 격리한 문서 하나. cause는 분류용(예외 타입·상태 코드) — 보고에는 uri 해시·stage·reason만 나간다. */
public record DeadLetter(String uri, String stage, String message, Throwable cause) {}
