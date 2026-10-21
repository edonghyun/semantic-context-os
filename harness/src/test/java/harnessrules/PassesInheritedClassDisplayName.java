package harnessrules;

import org.junit.jupiter.api.DisplayName;

/** 통과 표본: 상속한 테스트만 가진 구현 클래스가 자기 @DisplayName을 단다. */
@DisplayName("구현 클래스도 제목을 단다")
abstract class PassesInheritedClassDisplayName extends RuleSamples.PassesDisplayName {}
