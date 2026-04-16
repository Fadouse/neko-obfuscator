val libs = rootProject.the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":neko-api"))
    implementation(libs.findLibrary("asm-core").get())
    implementation(libs.findLibrary("asm-tree").get())
    implementation(libs.findLibrary("asm-commons").get())
    implementation(libs.findLibrary("asm-analysis").get())
    implementation(libs.findLibrary("asm-util").get())
    implementation(libs.findLibrary("slf4j-api").get())
}
