plugins {
    alias(mihonx.plugins.android.application)
}

android {
    namespace = "app.mihon.ftp.worker"

    defaultConfig {
        applicationId = "app.mihon.ftp.worker"
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.1.0")
}
