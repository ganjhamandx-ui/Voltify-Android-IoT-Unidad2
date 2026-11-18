
plugins {
    alias(libs.plugins.android.application)
}

// Vincula Android con el proyecto Firebase configurado.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.voltify"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cl.voltify.app"
        minSdk = 24
        targetSdk = 37

        versionCode = 15
        versionName = "12.3"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Componentes de Android y Material Design.
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // BoM administra las versiones compatibles de Firebase.
    implementation(
        platform("com.google.firebase:firebase-bom:34.8.0")
    )

    // Autenticación: usuarios, correo y contraseña.
    implementation("com.google.firebase:firebase-auth")

    // Realtime Database: datos dinámicos y monitoreo.
    implementation("com.google.firebase:firebase-database")

    // Cloud Firestore: perfiles y dispositivos.
    implementation("com.google.firebase:firebase-firestore")

    // Dependencias para pruebas.
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
