plugins {
    java
}

// 이클립스 4.6·BIRT 디자이너 4.8 번들과 같은 Java 8 바이트코드 (실행은 IntelliJ 내장 Java)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

sourceSets {
    main {
        // 검사·파라미터·PDF/HTML(birt-cli) 코드도 설계 화면 서버 안에서 같이 씀 (별도 BIRT 런타임 없이 같은 엔진)
        java.setSrcDirs(listOf("src", "../cli/src/main/java"))
        resources.setSrcDirs(emptyList<String>())
    }
}

val eclipsePlugins = findProperty("eclipsePlugins") as String?
    ?: error("이클립스 plugins 폴더가 없습니다 — local.properties에 eclipsePlugins=... (local.properties.example 참고)")

// 번들 안에 든 jar(Bundle-ClassPath) — BIRT JDBC 번들의 oda-jdbc.jar 등은 바깥 jar만으로는 컴파일 클래스패스에 안 잡힘
val nestedJars = tasks.register<Sync>("nestedJars") {
    from(zipTree("$eclipsePlugins/org.eclipse.birt.report.data.oda.jdbc_4.8.0.v201806261756.jar")) { include("oda-jdbc.jar") }
    into(layout.buildDirectory.dir("nested"))
}

dependencies {
    // 컴파일에만 씀 — 실행할 땐 OSGi가 번들끼리 연결함
    compileOnly(fileTree(eclipsePlugins) { include("*.jar") })
    compileOnly(files(layout.buildDirectory.file("nested/oda-jdbc.jar")).builtBy(nestedJars))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// OSGi 번들 jar — 이름은 bundles.info 관례(이름_버전.jar)
tasks.jar {
    archiveFileName.set("io.github.intellijbirt.render_1.0.0.jar")
    manifest {
        from("META-INF/MANIFEST.MF")
    }
    from("plugin.xml")
}
