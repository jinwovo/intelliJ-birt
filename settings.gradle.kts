rootProject.name = "intelliJ-birt"

// intellij : IntelliJ 플러그인 (rptdesign 편집기 탭·도구 창·구조 보기·저장·검사·미리보기 연결)
// render   : 이클립스 BIRT 디자이너 4.8을 돌리는 OSGi 번들 — 플러그인이 별도 JVM으로 상주 실행 (편집기·보조 창 끼워 넣기, 검사·PDF·HTML)
//   cli/src의 검사·파라미터·PDF/HTML 코드도 render 번들에 같이 컴파일됨 (따로 실행하지 않음)
include("intellij", "render")

// PC마다 다른 경로(ideaPath, eclipsePlugins) — local.properties (git에 안 올림, local.properties.example 참고)
//   명령줄 -P로 준 값이 있으면 그쪽이 우선
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.isFile) {
        f.reader(Charsets.UTF_8).use { load(it) }
    }
}
gradle.beforeProject {
    localProps.forEach { (k, v) ->
        if (!hasProperty(k as String)) {
            extensions.extraProperties[k] = v
        }
    }
}
