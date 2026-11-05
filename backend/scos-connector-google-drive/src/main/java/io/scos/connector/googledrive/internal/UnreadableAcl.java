package io.scos.connector.googledrive.internal;

/** permissions.list의 확정 독자 거부. 재시도·디렉터리 전체성 실패와 구분한다. */
final class UnreadableAcl extends RuntimeException {}
