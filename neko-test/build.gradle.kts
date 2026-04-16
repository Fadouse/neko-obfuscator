val libs = rootProject.the<VersionCatalogsExtension>().named("libs")

tasks.test {
    jvmArgs("-Xss4m") // Larger stack for SSA rename on deep CFGs
    maxHeapSize = "512m"
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
