import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * dbTest 작업끼리 차례로 돌게 하는 공유 자원(maxParallelUsages = 1). 구성 캐시에서는 여러 모듈의 dbTest가 동시에 돌 수 있는데,
 * 작업마다 포크 수만큼 PostgreSQL 컨테이너를 띄우므로(TESTING §6.3) 그대로 두면 컨테이너가 모듈 수만큼 곱해진다.
 */
abstract class PostgresContainers : BuildService<BuildServiceParameters.None>
