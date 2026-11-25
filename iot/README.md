# Voltify físico con Firebase Realtime Database

## Qué se integra

Arduino Uno R3 e INA219 → UART a 9600, RX D2 / TX D1 del NodeMCU → Wi-Fi de 2,4 GHz → HTTPS → Realtime Database → Voltify Android. Los sketches de revisión se conservaron tal como están publicados en `main`. Son revisiones de código fuente. Los respaldos binarios del firmware grabado permanecen separados.

El sketch nuevo valida `V`, `I`, `P`, `POR`, `EST`, `E` y `C` en el orden del protocolo existente. Envía un registro por bloque UTC de 30 segundos con la última muestra válida del bloque:

```text
https://voltify-e51f9-default-rtdb.firebaseio.com/
  users/{ownerUid}/consumptions/{id}
    id, artefactoId, artefactoNombre, artefactoCategoria, artefactoMarca, artefactoModelo
    consumoKwh, fecha, tipoFuente = IOT_REAL
    telemetria/
      muestraMs, voltajeV, corrienteMa, potenciaW, porcentaje, estado
      energiaTotalKwh, costoTotalClp
```

`fecha` es el inicio del bloque y `muestraMs` es el instante en que el ESP8266 procesó la trama. Ambos son milisegundos Unix. `consumoKwh` suma únicamente diferencias consecutivas plausibles del contador `E`. Un reinicio del Arduino, un salto implausible o una discontinuidad superior a 15 segundos reinicia la referencia. El acumulado y el costo que muestra la tarjeta corresponden a la placa. Los reportes Android mantienen su cálculo a partir de `consumoKwh` y su tarifa.

El ID es `1900000000 + slot × 10000000 + bloque`, donde el bloque cuenta intervalos de 30 segundos desde 2026-01-01 UTC. Los slots 0 a 23 son únicos **por propietario** durante la vida del historial. El PUT usa el mismo ID en cada reintento y las reglas solo autorizan la creación. Esto evita que un reintento, un reinicio o dos teléfonos sumen otra vez el bloque. El rango cubre aproximadamente 9,5 años desde la fecha de referencia. Antes de aprovisionar, comprobar que ese rango esté libre de IDs históricos en el propietario.

Android consume los registros por el listener existente. La pantalla **Dispositivos** muestra la última muestra de cada artefacto físico activo y en monitoreo. Al caducar la muestra vuelve a indicar espera. El historial conserva los incrementos ya sincronizados y Android no reenvía las muestras físicas.

## Aprovisionamiento pendiente en Firebase Console

Ejecutar estos pasos solo con autorización para modificar el proyecto `voltify-e51f9`.

1. Respaldar las reglas RTDB publicadas y compararlas con `firebase_database_rules.json`. Integrar el cambio de reglas sobre la configuración vigente y probar el resultado antes de publicarlo. Cualquier permiso de lectura o escritura concedido en un ancestro se propaga a sus descendientes. Revisar especialmente `/`, `/users` y `/users/{uid}`. Confirmar también que Firestore exige correo verificado para su acceso por usuario y revisar si App Check impone requisitos al cliente REST.
2. Usar un artefacto de prueba del propietario con fuente **IoT real**. Registrar su clave decimal e ID numérico de `/users/{ownerUid}/artifacts/{artifactKey}`. Dejar sus nombres y metadatos listos para copiar en la configuración local.
3. Confirmar que Email/Password está habilitado. En Authentication → Usuarios, crear una cuenta exclusiva para el ESP8266 con alias dedicado de control del proyecto y contraseña aleatoria robusta. Mantener el correo **sin verificar**. Anotar el UID de esa cuenta y por separado el UID del propietario. La cuenta del propietario sigue autenticándose en Android.
4. En Realtime Database → Datos, crear individualmente `/iotDeviceBindings/{deviceUid}` con `enabled` booleano, `ownerUid` texto, `artifactKey` texto y `slot` numérico entero de 0 a 23. Usar un slot libre para ese propietario. Las reglas de cliente no permiten leer ni escribir las vinculaciones. Para revocar acceso, poner `enabled` en `false` y deshabilitar la cuenta de la placa en Authentication. Conservar la vinculación deshabilitada y el slot histórico.
5. Aplicar las reglas revisadas únicamente cuando lo autorice el responsable del proyecto. Verificar con el emulador antes de repetir en producción. Si el API key Android existente está restringido al paquete Android, usar una clave cliente del mismo proyecto compatible con Identity Toolkit y Secure Token para el ESP8266.

La cuenta dedicada permite aprovisionar el prototipo sin servidor adicional y limita la escritura a consumos nuevos de un artefacto IoT vinculado. Firebase Auth REST devuelve un ID token y un refresh token. El ESP8266 renueva el ID token antes de su expiración, y ante un fallo de renovación por credenciales inválidas vuelve a iniciar sesión con la cuenta dedicada. Diagnostica por códigos HTTP sin imprimir credenciales ni URLs autenticadas. [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth/) · [Autenticación de RTDB REST](https://firebase.google.com/docs/database/rest/auth)

## Preparación de la placa

1. Abrir `firmware/esp8266/Voltify_NodeMCU_Firebase/Voltify_NodeMCU_Firebase.ino` en Arduino IDE. Usar **NodeMCU 1.0 (ESP-12E Module)** (`esp8266:esp8266:nodemcuv2`), ESP8266 core 3.1.2 y ArduinoJson 7.4.2. EspSoftwareSerial 8.0.1 viene con el core.
2. Copiar `VoltifyConfig.example.h` al mismo directorio como `VoltifyConfig.local.h`. Completar Wi-Fi de 2,4 GHz, API key cliente, credenciales y UID de la placa, UID del dueño, artefacto y slot. Copiar los cuatro campos de descripción del artefacto para mantener sus etiquetas históricas. Git ignora el archivo local. El binario compilado con datos reales contiene la contraseña de la placa y debe quedar local.
3. Compilar con **Verificar**. El firmware espera hora SNTP y valida TLS contra GTS Root R1–R4, incluidos en el código. La red debe resolver los hosts de Firebase y permitir SNTP y HTTPS. Si el operador de los endpoints cambia sus raíces, actualizar las anclas desde el repositorio oficial y recompilar. [Google Trust Services](https://pki.goog/repository/)
4. Cuando estén autorizadas la programación del ESP8266 y la actualización de Android, instalar el build de integración mediante el procedimiento acordado para preservar los datos de la aplicación. Si Android rechaza la actualización por la firma, detenerse y usar una compilación compatible con la firma de la instalación existente.

El ESP8266 conserva un bloque abierto y un solo envío pendiente. Si la red se interrumpe, un bloque reciente reemplaza al anterior pendiente y las muestras de más de 90 segundos se descartan. El cliente HTTPS síncrono puede interrumpir temporalmente el procesamiento de UART durante la conexión o la respuesta. El búfer tiene tamaño fijo y se descartan las tramas acumuladas durante operaciones largas porque UART carece de hora de origen. Tras una caída larga puede haber huecos en el historial. La resolución de `E` es de 0,000001 kWh y con cargas pequeñas pueden aparecer bloques de incremento cero. En uso continuo se generan hasta 2.880 filas por artefacto al día y la sincronización existente lee la colección completa. Limitar la duración de las sesiones del prototipo.

## Una prueba física controlada

1. Con la batería desconectada, confirmar el proyecto, los IDs y la vinculación. Activar monitoreo del artefacto de prueba en Android y verificar en RTDB que `monitoreando` es `true`.
2. Tras autorizar el paso de programación, cargar el nuevo sketch **solo a COM4**. Abrir el Monitor Serial de COM4 a 9600 y esperar Wi-Fi, Auth HTTP 200 y RTDB HTTP 204. Mantener el Arduino de COM3 con el firmware instalado.
3. Energizar el montaje de prueba existente y esperar entre 30 y 60 segundos. En RTDB comprobar la aparición de un ID nuevo en el UID y artefacto esperados, y que `telemetria` refleja la carga. Comprobar en **Dispositivos** el voltaje, corriente, potencia, estado y hora. En historial, contrastar el incremento por bloque con `consumoKwh`.
4. Desconectar la batería de prueba. Con el INA219 y los microcontroladores aún encendidos, comprobar que la siguiente muestra refleja el cambio de carga. Detener el monitoreo del artefacto de prueba y deshabilitar su vinculación si termina la demostración.

## Verificaciones locales

Desde la raíz del repositorio, con JDK de Android Studio, Arduino CLI y los paquetes instalados:

```powershell
.\gradlew.bat --offline assembleDebug testDebugUnitTest
arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 iot/firmware/esp8266/Voltify_NodeMCU_Firebase
```

Pruebas del parser, agregación, salida JSON y clasificación HTTP con un compilador C++17 y los headers de ArduinoJson:

```powershell
New-Item -ItemType Directory -Force iot/build | Out-Null
c++ -std=c++17 -I RUTA_A_ARDUINOJSON/src iot/tests/telemetry_test.cpp -o iot/build/telemetry_test.exe
.\iot\build\telemetry_test.exe
```

Pruebas de Auth y Realtime Database con un proyecto de emulador:

```powershell
npm install --prefix iot/build/emulator firebase-tools @firebase/rules-unit-testing firebase
node iot/build/emulator/node_modules/firebase-tools/lib/bin/firebase.js --config firebase.iot-emulator.json --project demo-voltify-iot emulators:exec --only auth,database 'node iot/tests/rtdb.test.cjs'
```
