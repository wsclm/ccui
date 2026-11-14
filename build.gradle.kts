plugins { id("com.android.application") version "9.0.0" apply false }
allprojects {
    providers.gradleProperty("ccui.buildRoot").orNull?.let { root ->
        layout.buildDirectory.set(file("$root/${if (project == rootProject) "root" else project.name}"))
    }
}
