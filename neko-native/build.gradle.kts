val libs = rootProject.the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":neko-api"))
    implementation(project(":neko-core"))
    implementation(libs.findLibrary("asm-core").get())
    implementation(libs.findLibrary("asm-tree").get())
    implementation(libs.findLibrary("slf4j-api").get())
}
