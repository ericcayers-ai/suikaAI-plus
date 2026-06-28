val dyn4jVersion: String by project
val slf4jVersion: String by project

dependencies {
    implementation("org.dyn4j:dyn4j:${dyn4jVersion}")
    implementation("org.slf4j:slf4j-api:${slf4jVersion}")
    testImplementation("org.slf4j:slf4j-simple:${slf4jVersion}")
}
