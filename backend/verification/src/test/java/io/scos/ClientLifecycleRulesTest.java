package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("클라이언트 자원은 생성자 밖에서 열고 실패 정리 구조를 둔다(B11·CG-6)")
class ClientLifecycleRulesTest {
  private static final Set<String> OWNERS =
      Set.of("ClientAssembly", "LocalEngine", "CollectionSession", "CollectionCycles");
  private static final Set<String> IO_OWNERS =
      Set.of("PrivateFiles", "SqliteIndex", "StateStore", "CollectLock", "Executors");

  static List<JavaMethodCall> constructorAcquisitions(JavaClass type) {
    List<JavaMethodCall> acquisitions = new ArrayList<>();
    Set<JavaCodeUnit> visited = new HashSet<>();
    for (var constructor : type.getConstructors())
      collectAcquisitions(constructor, visited, acquisitions);
    return acquisitions;
  }

  /** import된 클라이언트·표본의 정적으로 해석되는 호출만 따른다. 동적 디스패치·리플렉션은 검사하지 않는다. */
  private static void collectAcquisitions(
      JavaCodeUnit unit, Set<JavaCodeUnit> visited, List<JavaMethodCall> acquisitions) {
    if (!visited.add(unit)) return;
    unit.getMethodCallsFromSelf().stream()
        .filter(call -> IO_OWNERS.contains(call.getTargetOwner().getSimpleName()))
        .forEach(acquisitions::add);
    for (var call : unit.getCallsFromSelf()) {
      var target = call.getTarget().resolveMember().orElse(null);
      if (target == null) continue;
      String pkg = target.getOwner().getPackageName();
      if (pkg.startsWith("io.scos.client") || pkg.startsWith("scosrules"))
        collectAcquisitions(target, visited, acquisitions);
    }
  }

  @Test
  @DisplayName("자원 소유자의 생성자는 파일이나 실행기를 열지 않는다")
  void constructorsDoNotAcquireResources() {
    // given
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.scos.client");

    // when
    var acquisitions =
        classes.stream()
            .filter(type -> OWNERS.contains(type.getSimpleName()))
            .flatMap(type -> constructorAcquisitions(type).stream())
            .map(JavaMethodCall::getDescription)
            .toList();

    // then
    assertThat(acquisitions).isEmpty();
  }

  @Test
  @DisplayName("색인 소유권을 넘기는 팩토리는 실패 catch에 닫기와 재던지기를 둔다")
  void factoriesDeclareFailureCleanup() throws IOException {
    // given
    var sources =
        ProductSourceTree.files().stream()
            .filter(path -> path.startsWith(Path.of("scos-client")))
            .toList();

    // when
    var unguarded = new ArrayList<String>();
    for (Path source : sources)
      for (int line : FactoryFailureCleanup.unguardedOpenings(Files.readString(source)))
        unguarded.add(source + ":" + line);

    // then
    assertThat(unguarded).isEmpty();
  }
}
