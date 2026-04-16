// neko-runtime: standalone, no external dependencies
// NOTE: We compile with Java 17 but target Java 8 bytecode compatibility
// The runtime classes use only APIs available in Java 8
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
