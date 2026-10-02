plugins { kotlin("jvm") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
tasks.register<JavaExec>("coreChecks") { dependsOn("testClasses"); classpath = sourceSets["test"].runtimeClasspath; mainClass.set("dev.eventrelay.core.CoreChecksKt") }
