import org.gradle.api.provider.Property

/** 모듈별 NullAway opt-in(scos.java-module): `scosNullAway { enabled = true }`이면 main 컴파일에서 NullAway가 ERROR다. */
interface ScosNullAway {
    val enabled: Property<Boolean>
}
