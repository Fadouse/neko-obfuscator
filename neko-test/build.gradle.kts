val libs = rootProject.the<VersionCatalogsExtension>().named("libs")

tasks.test {
    useJUnitPlatform()
    dependsOn(":neko-cli:installDist")
    jvmArgs("-Xss4m") // Larger stack for SSA rename on deep CFGs
    maxHeapSize = "512m"
    systemProperty("neko.test.projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("neko.test.jarsDir", rootProject.projectDir.resolve("test-jars").absolutePath)
    systemProperty("neko.test.configsDir", rootProject.projectDir.resolve("configs").absolutePath)
    systemProperty("neko.test.cliPath", rootProject.projectDir.resolve("neko-cli/build/install/neko-cli/bin/neko-cli").absolutePath)
    systemProperty(
        "neko.test.nativeWorkDir",
        layout.buildDirectory
            .dir("test-native")
            .get()
            .asFile.absolutePath,
    )
}

dependencies {
    testImplementation(project(":neko-api"))
    testImplementation(project(":neko-config"))
    testImplementation(project(":neko-core"))
    testImplementation(project(":neko-transforms"))
    testImplementation(project(":neko-native"))
    testImplementation(project(":neko-runtime"))
    testImplementation(project(":neko-cli"))
    testImplementation(libs.findLibrary("asm-core").get())
    testImplementation(libs.findLibrary("asm-tree").get())
    testImplementation(libs.findLibrary("junit-api").get())
    testRuntimeOnly(libs.findLibrary("junit-engine").get())
    testRuntimeOnly(libs.findLibrary("logback").get())
}
