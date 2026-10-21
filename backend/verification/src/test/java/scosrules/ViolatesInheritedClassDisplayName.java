package scosrules;

/** 위반 표본: 상속한 테스트만 가진 구현 클래스. 계약의 @DisplayName은 상속되지 않는다. */
abstract class ViolatesInheritedClassDisplayName extends RuleSamples.PassesDisplayName {}
