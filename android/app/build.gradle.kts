import java.net.URI

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
android {
 namespace = "sa.rihla"; compileSdk = 37
 compileSdkMinor = 2
 buildToolsVersion = "37.0.0"
 defaultConfig { applicationId = "sa.rihla"; minSdk = 26; targetSdk = 37; versionCode = 3; versionName = "1.2" }
 bundle { language { enableSplit = false } }
 buildFeatures { compose = true }
 testOptions {
  unitTests.isIncludeAndroidResources = true
  unitTests.all { test ->
   // Robolectric fetches Android runtime jars from its test JVM, outside Gradle resolution.
   System.getenv("HTTPS_PROXY")?.let { URI(it) }?.let { proxy ->
    listOf("http", "https").forEach { protocol ->
     test.systemProperty("$protocol.proxyHost", proxy.host)
     test.systemProperty("$protocol.proxyPort", proxy.port.takeIf { it > 0 } ?: 80)
    }
   }
   System.getProperty("javax.net.ssl.trustStore")?.let { test.systemProperty("javax.net.ssl.trustStore", it) }
   test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.09.00"))
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
 implementation("androidx.room:room-runtime:2.8.5")
 implementation("androidx.room:room-ktx:2.8.5")
 ksp("androidx.room:room-compiler:2.8.5")
 implementation("androidx.datastore:datastore-preferences:1.2.1")
 implementation("com.google.android.gms:play-services-location:21.4.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.17")
}
