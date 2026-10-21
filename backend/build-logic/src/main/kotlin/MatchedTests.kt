import java.util.concurrent.atomic.AtomicLong
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * 이름만으로 부른 층 작업(`fastTest --tests X`)이 실행한 테스트 수의 합. 그 경우 일치가 없는 모듈은 건너뛰고,
 * 루트의 assertTestsMatched가 합이 0일 때만 실패시킨다.
 */
abstract class MatchedTests : BuildService<BuildServiceParameters.None> {
    private val count = AtomicLong()

    fun add(tests: Long) {
        count.addAndGet(tests)
    }

    fun total(): Long = count.get()
}
