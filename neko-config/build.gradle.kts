val libs = rootProject.the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":neko-api"))
    implementation(libs.findLibrary("snakeyaml").get())
}
