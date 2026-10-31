import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * 모듈 사이 의존 방향 검사(공개 배포 단계 4 T6). 허용 표의 모듈이 모듈 목록(ScosModules)·settings include와 같고, main 클래스패스
 * (compileClasspath·runtimeClasspath)에 선언된 프로젝트 의존이 허용 표와 정확히 같고, 파일 의존이 없고, scos-spi에 외부 의존이 없고(compileOnly JSpecify 애노테이션만 예외),
 * starter·server 밖 제품 모듈의 컴파일 클래스패스에 Spring Boot가 없어야 한다. 값은 루트 build.gradle.kts가 모든 프로젝트를 평가한 뒤
 * 넣는다(클래스패스는 지연 해석).
 */
abstract class CheckModuleGraph : DefaultTask() {
    /** 모듈 → 허용한 프로젝트 의존(모듈 표). */
    @get:Input
    abstract val allowed: MapProperty<String, List<String>>

    /** 모듈 목록(ScosModules.products + support). */
    @get:Input
    abstract val modules: ListProperty<String>

    /** settings.gradle.kts가 include한 프로젝트. */
    @get:Input
    abstract val included: ListProperty<String>

    /** main 클래스패스에 파일 의존(files(…))을 둔 모듈. */
    @get:Input
    abstract val withFileDependencies: ListProperty<String>

    /** 모듈 → 선언한 프로젝트 의존. */
    @get:Input
    abstract val declared: MapProperty<String, List<String>>

    /** 모듈 → 선언한 외부 의존(BOM 제외). scos-spi만 본다. */
    @get:Input
    abstract val externalOfSpi: MapProperty<String, List<String>>

    /** 모듈 → 컴파일 클래스패스에 든 Spring Boot 모듈(BOM 제외). starter·server 밖 제품 모듈만 본다. */
    @get:Input
    abstract val bootOnCompileClasspath: MapProperty<String, List<String>>

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val allowed = allowed.get().mapValues { it.value.toSet() }
        val declared = declared.get().mapValues { it.value.toSet() }
        val modules = modules.get().toSet()
        val included = included.get().toSet()
        if (allowed.keys != modules) problems += "허용 표와 모듈 목록(ScosModules)이 다르다: 표에만 ${(allowed.keys - modules).sorted()}, 목록에만 ${(modules - allowed.keys).sorted()}"
        if (included != modules) problems += "settings include와 모듈 목록(ScosModules)이 다르다: include에만 ${(included - modules).sorted()}, 목록에만 ${(modules - included).sorted()}"
        (allowed.keys + declared.keys).sorted().forEach { module ->
            val extra = declared[module].orEmpty() - allowed[module].orEmpty()
            val missing = allowed[module].orEmpty() - declared[module].orEmpty()
            if (module !in declared) problems += "$module: 허용 표에 있으나 프로젝트가 없다"
            if (module !in allowed) problems += "$module: 허용 표에 없다"
            if (extra.isNotEmpty()) problems += "$module: 허용하지 않은 의존 ${extra.sorted()}"
            if (missing.isNotEmpty()) problems += "$module: 허용 표에 있으나 선언하지 않은 의존 ${missing.sorted()}(표를 고친다)"
        }
        withFileDependencies.get().sorted().forEach { problems += "$it: 파일 의존 금지(project 의존으로 선언한다)" }
        externalOfSpi.get().filterValues { it.isNotEmpty() }.forEach { (module, deps) -> problems += "$module: 외부 의존 $deps(JDK만 쓴다)" }
        bootOnCompileClasspath.get().filterValues { it.isNotEmpty() }.forEach { (module, deps) ->
            problems += "$module: 컴파일 클래스패스에 Spring Boot $deps(starter·server만 쓴다)"
        }
        if (problems.isNotEmpty()) throw GradleException("모듈 의존 검사 실패:\n" + problems.joinToString("\n") { "  - $it" })
    }
}
