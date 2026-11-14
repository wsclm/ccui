plugins { id("com.android.application") }
android {
    namespace = "com.ccui.workbench"
    compileSdk = 37
    defaultConfig { applicationId = "com.ccui.workbench"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("com.googlecode.plist:dd-plist:1.28")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
