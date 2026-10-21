package io.scos.harness.server;

import io.scos.harness.fixture.FixtureSpec;
import io.scos.harness.mcp.JsonRpcStdioClient;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 구현별 서버 기동 방법. 하네스는 이 인터페이스로만 구현을 안다. 같은 fixture를 자기 저장소에 적재(prepare)하고, 고정 시각·카세트·미스 로그를 환경으로 받는
 * 프로세스 명령(launch)을 만든다. 작업 디렉터리는 하네스가 만들고 지운다.
 */
public interface ServerLauncher {
  String implementationId();

  /** fixture를 이 구현의 저장소로 적재한다(TS: v2 SQLite 파일, Java: PostgreSQL — P2). 반환은 서버에 넘길 저장소 위치. */
  Path prepare(FixtureSpec fixture, Path workDir) throws IOException;

  JsonRpcStdioClient.Launch launch(FixtureSpec fixture, Path workDir, Path store, HarnessEnv env);

  /** 하네스가 모든 구현에 공통으로 주는 고정 입력. */
  record HarnessEnv(String nowIso, Path cassette, Path missLog, String spaceId) {}
}
