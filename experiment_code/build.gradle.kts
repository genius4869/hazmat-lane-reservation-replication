plugins { java }

// Use local, separately installed tools so a clean build can run offline.
fun installPath(name: String): File = file(System.getenv(name)
    ?: throw GradleException("Set $name before running the build."))
val kotlinHome = installPath("KOTLIN_HOME")
val stdlib = System.getenv("KOTLIN_STDLIB")?.let { file(it) }
    ?: kotlinHome.resolve("lib/kotlin-stdlib.jar")
val cplexJar = System.getenv("CPLEX_JAR")?.let { file(it) }
    ?: installPath("CPLEX_STUDIO_DIR").resolve("cplex/lib/cplex.jar")
val kotlinClasses = layout.buildDirectory.dir("classes/kotlin/main")
val compileDataClass by tasks.registering(JavaExec::class) {
    val compiler = kotlinHome.resolve("lib/kotlin-compiler.jar")
    val source = file("src/main/kotlin/DataCreator.kt")
    inputs.files(source, compiler, stdlib)
    outputs.dir(kotlinClasses)
    classpath = files(compiler)
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    args("-kotlin-home", kotlinHome, "-language-version", "2.1", "-api-version", "2.1",
        "-jvm-target", "1.8", "-no-stdlib", "-classpath", stdlib, "-d",
        kotlinClasses.get().asFile, source)
}
dependencies { implementation(files(cplexJar, stdlib)) }
sourceSets.main { output.dir(mapOf("builtBy" to compileDataClass), kotlinClasses) }
tasks.compileJava {
    dependsOn(compileDataClass)
    classpath += files(kotlinClasses)
    options.release.set(15)
    options.encoding = "UTF-8"
    options.isFork = true
    options.forkOptions.jvmArgs = listOf("-Duser.language=en", "-Duser.country=US")
}
tasks.register<JavaExec>("run") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("Replication")
    doFirst {
        val os = System.getProperty("os.name")
        val (suffix, key) = when {
            os.contains("windows", ignoreCase = true) -> "x64_win64" to "PATH"
            os.contains("mac", ignoreCase = true) -> "x86-64_osx" to "DYLD_LIBRARY_PATH"
            else -> "x86-64_linux" to "LD_LIBRARY_PATH"
        }
        val nativeDir = System.getenv("CPLEX_NATIVE_DIR")?.let { file(it) }
            ?: installPath("CPLEX_STUDIO_DIR").resolve("cplex/bin/$suffix")
        require(nativeDir.isDirectory) { "Set CPLEX_NATIVE_DIR to the native CPLEX library directory." }
        systemProperty("java.library.path", nativeDir.absolutePath)
        environment(key, nativeDir.absolutePath + File.pathSeparator + (System.getenv(key) ?: ""))
        maxHeapSize = System.getenv("HAZMAT_HEAP") ?: "1024m"
    }
}
