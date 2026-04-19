val libs = rootProject.the<VersionCatalogsExtension>().named("libs")

plugins {
    application
}

application {
    mainClass.set("dev.nekoobfuscator.cli.Main")
    if ((findProperty("nekoNativeDebug")?.toString()?.toBoolean() == true)
        || (System.getenv("NEKO_NATIVE_BUILD_DEBUG")?.toBoolean() == true)) {
        applicationDefaultJvmArgs = listOf("-Dneko.native.debug=true")
    }
}

dependencies {
    implementation(project(":neko-api"))
    implementation(project(":neko-config"))
    implementation(project(":neko-core"))
    implementation(project(":neko-transforms"))
    implementation(project(":neko-native"))
    implementation(project(":neko-runtime"))
    implementation(libs.findLibrary("picocli").get())
    runtimeOnly(libs.findLibrary("logback").get())
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "dev.nekoobfuscator.cli.Main")
    }
}
