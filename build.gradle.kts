// Configuración de proyecto. Firebase se deja listo para activarse al agregar google-services.json.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
