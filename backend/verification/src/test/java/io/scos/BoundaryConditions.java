package io.scos;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.PackageMatchers;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** 공개 경계 규칙(단계 2 §4.2)의 사용자 정의 조건: 모듈 루트 비교(R3)와 공개 시그니처 검사(R3′). */
final class BoundaryConditions {
  private BoundaryConditions() {}

  /**
   * 대상이 internal 패키지이고 접근하는 쪽과 모듈({@link ProductModules})이 다르면 위반이다. 대상 internal이 표의 어느 모듈에도 속하지
   * 않아도 위반이다 — 표에서 빠진 모듈은 양쪽 다 "모듈 없음"으로 같아 보여 검사를 조용히 벗어난다.
   */
  static ArchCondition<JavaClass> dependOnInternalOfAnotherModule() {
    return new ArchCondition<>("depend on internal of another module") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        Optional<String> originModule = ProductModules.moduleOf(origin.getPackageName());
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          String target = dependency.getTargetClass().getPackageName();
          if (!isInternal(target)) continue;
          Optional<String> targetModule = ProductModules.moduleOf(target);
          if (targetModule.isEmpty()) {
            events.add(
                SimpleConditionEvent.satisfied(
                    dependency,
                    "internal 패키지 "
                        + target
                        + "가 모듈 표(ProductModules)에 없다: "
                        + dependency.getDescription()));
          } else if (!targetModule.equals(originModule)) {
            events.add(SimpleConditionEvent.satisfied(dependency, dependency.getDescription()));
          }
        }
      }
    };
  }

  /** 인자·반환·예외 타입(제네릭 인자 포함)에 주어진 패키지 패턴의 타입이 나오면 위반이다. */
  static ArchCondition<JavaCodeUnit> notReferenceTypesInSignatureFrom(String... packagePatterns) {
    PackageMatchers matchers = PackageMatchers.of(packagePatterns);
    return new ArchCondition<>(
        "not reference types in signature from " + String.join(", ", packagePatterns)) {
      @Override
      public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
        Stream.of(
                codeUnit.getParameterTypes().stream()
                    .flatMap(t -> t.getAllInvolvedRawTypes().stream()),
                codeUnit.getReturnType().getAllInvolvedRawTypes().stream(),
                codeUnit.getThrowsClause().getTypes().stream())
            .flatMap(s -> s)
            .filter(type -> matchers.test(type.getPackageName()))
            .distinct()
            .sorted(Comparator.comparing(JavaClass::getName))
            .forEach(
                type ->
                    events.add(
                        SimpleConditionEvent.violated(
                            codeUnit, codeUnit.getFullName() + " exposes " + type.getName())));
      }
    };
  }

  private static boolean isInternal(String packageName) {
    return packageName.endsWith(".internal") || packageName.contains(".internal.");
  }
}
