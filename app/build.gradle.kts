plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose"); id("com.google.devtools.ksp") }
android {
 namespace="dev.eventrelay.app"; compileSdk=35
 defaultConfig { applicationId="dev.eventrelay.app"; minSdk=26; targetSdk=35; versionCode=4; versionName="0.3.0";buildConfigField("boolean","GMAIL_ENABLED",providers.gradleProperty("eventrelayGmailEnabled").map{it.toBoolean()}.orElse(false).get().toString()) }
 // Signing is performed separately; no private key or password files are loaded.
 buildTypes { getByName("debug") { signingConfig=null } }
 buildFeatures { compose=true;buildConfig=true }
 testOptions {
  unitTests.isIncludeAndroidResources=true
  unitTests.all {
   val testHome=project.layout.buildDirectory.dir("test-home").get().asFile
   it.systemProperty("user.home",testHome.absolutePath)
   it.doFirst { testHome.mkdirs() }
   it.systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2")
   listOf("http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort","javax.net.ssl.trustStore").forEach { key -> System.getProperty(key)?.let { value -> it.systemProperty(key,value) } }
  }
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies {
 testImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testImplementation("androidx.room:room-testing:2.6.1")
 implementation("com.google.android.gms:play-services-auth:22.0.0")
 implementation(project(":core"))
 implementation("androidx.core:core-ktx:1.15.0")
 implementation(platform("androidx.compose:compose-bom:2025.02.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("androidx.room:room-ktx:2.6.1")
 ksp("androidx.room:room-compiler:2.6.1")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
