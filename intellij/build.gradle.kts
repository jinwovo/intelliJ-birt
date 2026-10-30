import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 지원 범위: IntelliJ IDEA 2025.1(빌드 251)부터 — 가장 낮은 버전을 SDK로 컴파일하고, 검사 도구로 위 버전들까지 확인 (verifyPlugin)
//   버전마다 다른 모듈 이름(2026.2에서 나뉜 XML 구조 보기·JCEF 등)은 쓰지 않고 모든 버전에 있는 기본 확장점만 씀
val platformVersion = (findProperty("platformVersion") as String?) ?: "2025.1"
val ideaPath = findProperty("ideaPath") as String?   // (선택) 설치된 최신 IntelliJ — 검사 대상에 넣음

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaUltimate, platformVersion)
        pluginVerifier()
    }
}

// 설계 화면(Layout·Master Page) — 이클립스 BIRT 디자이너 4.8 코드를 별도 JVM(OSGi)으로 실행해서 그림 → 이클립스와 픽셀까지 같음
//   이클립스 plugins에서 필요한 번들만 복사 (render/bundles.txt — 실행 중 실제 연결을 따라 뽑은 목록)
//   판: full(기본, 공식 디자이너와 같은 데이터 소스·출력 형식 — bundles.txt + bundles-full.txt) / lite(-Pedition=lite, bundles.txt만)
val eclipsePlugins = findProperty("eclipsePlugins") as String?
    ?: error("이클립스 plugins 폴더가 없습니다 — local.properties에 eclipsePlugins=... (local.properties.example 참고)")
val edition = (findProperty("edition") as String?) ?: "full"
require(edition == "full" || edition == "lite") { "edition은 full 또는 lite: $edition" }
val pluginVersion = "0.13.0"
val bundleLists = listOf("bundles.txt") + if (edition == "full") listOf("bundles-full.txt") else emptyList()
val bundleLines = bundleLists.flatMap { rootProject.file("render/$it").readLines(Charsets.UTF_8) }
val designerBundles = bundleLines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(",")[2] }

// 서버가 읽는 번들 목록 (designer/bundles.txt) — 이 판의 목록을 합친 것
val designerBundleList by tasks.registering {
    val out = layout.buildDirectory.file("designer/bundles.txt")
    inputs.property("edition", edition)
    inputs.files(bundleLists.map { rootProject.file("render/$it") })
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(bundleLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}

tasks.prepareSandbox {
    from(eclipsePlugins) {
        include(designerBundles.flatMap { listOf(it, "$it/**") })
        exclude("*/drivers/*.jar")   // 이클립스에 넣어 둔 JDBC 드라이버(상용 라이선스일 수 있음)는 배포본에 넣지 않음
        into("intellij-birt/designer/plugins")
    }
    // 폴더로 설치해야 하는 번들(Eclipse-BundleShape: dir)이 이클립스에 jar로 있으면 풀어서 — JDBC 번들의 drivers 폴더에
    // 드라이버 jar를 넣음 (이클립스 Manage Drivers와 같은 자리, BirtDesignerServer.installDrivers)
    designerBundles.filter { !it.endsWith(".jar") && !file("$eclipsePlugins/$it").isDirectory && file("$eclipsePlugins/$it.jar").isFile }
        .forEach { bundle ->
            from(zipTree("$eclipsePlugins/$bundle.jar")) {
                exclude("drivers/*.jar")
                into("intellij-birt/designer/plugins/$bundle")
            }
        }
    from(project(":render").tasks.named("jar")) {
        into("intellij-birt/designer/plugins")
    }
    from(designerBundleList) {
        into("intellij-birt/designer")
    }
    from(rootProject.file("render")) {
        include("java-modern.profile")
        into("intellij-birt/designer")
    }
    // 라이선스 고지 — 이 저장소 코드(MIT), 들어 있는 이클립스·제3자 번들 목록과 라이선스, EPL 원문
    from(rootProject.files("LICENSE", "THIRD-PARTY-NOTICES.md")) {
        into("intellij-birt")
    }
    from(rootProject.file("licenses")) {
        into("intellij-birt/licenses")
    }
}

// 배포 zip: intellij-birt-<버전>.zip (풀판) / intellij-birt-lite-<버전>.zip (경량판)
tasks.buildPlugin {
    archiveFileName.set(if (edition == "full") "intellij-birt-$pluginVersion.zip" else "intellij-birt-lite-$pluginVersion.zip")
}

// 2025.x 내장 Java가 21 — 그 버전 바이트코드로 (2026.x의 Java 25에서도 그대로 돎)
//   컴파일러는 Gradle을 돌리는 JBR 25 (JDK 21을 따로 설치하지 않음)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

intellijPlatform {
    projectName = "intellij-birt"   // 배포 zip·설치 폴더 이름
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, platformVersion)
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
            if (ideaPath != null) {
                local(file(ideaPath))
            }
        }
    }
    pluginConfiguration {
        id = "io.github.intellijbirt"
        name = "Report Designer for BIRT"
        version = if (edition == "full") pluginVersion else "$pluginVersion-lite"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
