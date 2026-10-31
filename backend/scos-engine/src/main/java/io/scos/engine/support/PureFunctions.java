package io.scos.engine.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 순수 계산 모듈 선언(CODING-GUIDELINES §1.1 예외 2, TESTING §13.2): 입력→출력만 있고 의존성·상태가 없는 변환을 모은 역할 모듈 하나다. 이
 * 클래스의 public static 함수는 코딩 규약 규칙의 팩토리 요구에서 빠진다. 대신 구조 규칙({@code
 * ConventionRulesTest.pureFunctionModulesAreStateless})이 선언을 검증한다 — final, private 생성자만, 인스턴스 필드
 * 없음, static 필드는 모두 final, public static 함수의 인자에 계약(java 밖의 봉인되지 않은 인터페이스 — 포트) 없음. sealed 값 타입은
 * 받아도 된다.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface PureFunctions {}
