package io.scos.harness.testing;

import org.testcontainers.utility.DockerImageName;

/**
 * 하네스의 PostgreSQL 이미지(한 곳). backend `PostgresSupport.IMAGE`와 같은 참조여야 한다 — `PostgresImageTest`가 본다.
 */
public final class PostgresImage {
  /** 2026-10-15 확인: pg17 태그의 인덱스 digest(PostgreSQL 17.11, pgvector 0.8.7). 올릴 때는 TESTING §6 절차. */
  public static final String REFERENCE =
      "pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d";

  public static final DockerImageName IMAGE =
      DockerImageName.parse(REFERENCE).asCompatibleSubstituteFor("postgres");

  private PostgresImage() {}
}
