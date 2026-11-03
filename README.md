# Voltify — Android / IoT · Unidad 2

**Proyecto Kotlin + XML, Firebase Authentication y Firebase Realtime Database.**

- Versión fuente: **12.3** (`versionCode=15`)
- Paquete de instalación: **`cl.voltify.app`** (único `applicationId`)
- Paquete Kotlin / `namespace`: **`com.example.voltify`** (NO crea segunda app)
- Firebase: proyecto **`voltify-e51f9`** con rutas de datos existentes y UID individual
- Android: `minSdk 24`, `targetSdk 37`, Java 11, arquitectura Repository / ViewModel

## Qué hace la app

Registro e inicio de sesión, recuperación, dashboard, CRUD de artefactos (incluidas baja lógica y eliminación física del registro maestro con historial conservado), simulación dinámica y monitoreo múltiple, consumos en kWh, costo estimado, metas, consejos, reportes, gráficos y comparación, sincronización Wi-Fi/Internet mediante Firebase entre Android físicos y emulador.

Las lecturas **SIMULADA** proceden del modelo de simulación; **IOT_REAL** es la interfaz preparada para conectar el sensor físico más adelante. Las tarifas automáticas actuales **son estimaciones locales de demostración**; no se presentan como tarifa regulada oficial ni como API ya integrada.

## Cambios aplicados en esta versión

1. Login con contraseña existente: se valida la credencial en Firebase sin bloquearla por cambios de reglas de complejidad; política fuerte permanece en registro/recuperación.
2. Botón Ingresar protegido contra pulsaciones repetidas durante la solicitud.
3. IDs numéricos nuevos no secuenciales por teléfono; mantienen todos los IDs y datos previos sin migración masiva.
4. Lecturas SIMULADA agrupadas en bloques de 30 segundos por artefacto; dos teléfonos que observan la misma simulación comparten la misma clave y una transacción retiene el mayor acumulado, sin sumar dos fuentes.
5. Firebase RTDB con persistencia de escrituras y caché en disco para interrupciones normales de conexión.
6. Validaciones para artefactos, perfil, tarifas manuales y metas, congruentes con los límites remotos.
7. UI: botón Buscar sin partir la palabra, etiqueta `Sem.` más explícita, mensajes de simulación y tarifas de prueba.
8. Archivo de reglas propuesto más restrictivo a las rutas de escritura de cada usuario (requiere publicación manual y respaldo previo).

## Abrir y compilar

1. Descomprimir el ZIP y abrir en Android Studio la carpeta `Voltify_Android_V12_3` (la que contiene `settings.gradle.kts`).
2. Sincronizar Gradle. Conservar el `app/google-services.json` ya asociado al proyecto Firebase y no reemplazarlo por un archivo de otro proyecto.
3. Compilar **Build > Make Project**. Después seleccionar `Medium Phone API 37.0` y **Run**.
4. Comprobar versión instalada con ADB: `adb shell dumpsys package cl.voltify.app | findstr /i "versionName versionCode"`.
5. La actualización mantiene el mismo identificador `cl.voltify.app`: instalar sobre V12.2, NO borrar datos ni desinstalar la app funcionando.

### Si aparecen varios iconos Voltify

Un `AndroidManifest.xml` con **un** `MAIN/LAUNCHER` no puede crear tres instalaciones. Se detectaron anteriormente los paquetes `cl.voltify.app` y `com.example.voltify` en el emulador: el segundo corresponde al identificador antiguo, no al namespace de Kotlin.

Con PowerShell, **únicamente para el emulador**:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 shell pm list packages | findstr voltify
& $adb -s emulator-5554 uninstall com.example.voltify
& $adb -s emulator-5554 shell pm list packages | findstr voltify
```

Debe quedar `package:cl.voltify.app`. Si aún quedan dos accesos directos visuales del mismo paquete en la pantalla principal, quitar el acceso directo sobrante, **no desinstalar cl.voltify.app**. Si el usuario hablaba literalmente de Spotify, ese fenómeno es externo a Voltify.

## Evaluación / GitHub

El código puede publicarse en un repositorio (revisar primero datos sensibles). La guía sumativa solicita entregar por intranet un archivo `.txt` con el enlace GitHub. Tener listos Samsung, emulador y Firebase para demostración en vivo. Ver `PRUEBAS_ESENCIALES_SUMATIVA2.md`.

`google-services.json` es configuración de cliente; **no debe contener claves de servicio administrativas**, archivos de cuentas de servicio, tokens privados ni contraseñas. Para repositorio público revisar las restricciones de API y las reglas RTDB.

## Seguridad y límites

- Credenciales Firebase Authentication, sin contraseñas en RTDB; sesión verificada por correo.
- RTDB por `/users/{uid}`, reglas incluidas para revisión. **No son cambios aplicados automáticamente al Firebase existente**.
- Almacenamiento local cifrado mediante Android Keystore + AES-GCM; HTTPS (`usesCleartextTraffic=false`).
- Las normas ISO/IEC 27400 / ISO/IEC 27001 sirven de referencia para controles; no representa certificación.
- La persistencia RTDB conserva escrituras ordinarias, pero las **transacciones iniciadas offline no sobreviven a un reinicio de la app** (comportamiento de Firebase). Detener monitoreos antes de pruebas offline y verificar la recepción al reconectar.
- La integración de la API oficial de tarifas y el sensor INA219 / Arduino / ESP8266 físicos queda para otra unidad.
- **Este ZIP contiene código fuente; no se afirma que el APK haya compilado aquí.** Verificar Build en Android Studio y los flujos críticos antes de reemplazar la aplicación del Samsung.
