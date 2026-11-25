# Voltify IoT físico · Unidades 3 y 4

Arduino Uno R3 e INA219 → UART a 9600 → NodeMCU ESP8266 → HTTPS → Firebase RTDB → Android `cl.voltify.app`. Los sketches de revisión se conservan como referencia. El firmware bidireccional del Uno está en `firmware/arduino/Voltify_Arduino_Bidirectional`.

## Distribución de datos

- **Firebase Auth** identifica a los usuarios Android mediante el correo verificado. El NodeMCU usa Firebase Custom Authentication con UID fijo `voltify-01-nodemcu` y una sesión renovable aprovisionada desde la HP.
- **Cloud Firestore** conserva perfiles y documentos de dispositivos en las rutas existentes. Los ocho componentes se aprovisionan de la plantilla en `users/{ownerUid}/iotComponents/{componentId}`. Android consulta Firestore antes de enviar órdenes a esos destinos. El cliente y las reglas nuevas ya no usan la versión anterior del inventario en RTDB. Ante datos heredados, revisar y respaldar antes de migrarlos. Este cambio no borra registros.
- **Realtime Database** conserva `users/{ownerUid}/iotLive/{artifactKey}` (última lectura, objetivo de 2 s) y `users/{ownerUid}/consumptions/{id}` (bloques de 30 s). El bloque contiene solo el incremento plausible de energía y recibe un ID fijo por slot y bloque. Las lecturas físicas siguen marcadas `IOT_REAL`. Se preservan las operaciones `SIMULADA`.

## Preparación de Firebase · requiere autorización

1. En `voltify-e51f9`, respaldar y comparar las reglas publicadas. Revisar permisos heredados y si App Check de RTDB admite el cliente REST. Mantener los proveedores que usan los usuarios Android. El dispositivo no requiere activar el proveedor Anónimo ni crear un usuario con correo.
2. En Google Cloud, habilitar **IAM Service Account Credentials API** y seleccionar una cuenta de servicio firmante del proyecto, sin descargar su clave privada. Dar al operador de la HP permiso `iam.serviceAccounts.signBlob` sobre esa cuenta (por ejemplo, Service Account Token Creator limitado a esa cuenta). Usar una clave cliente del mismo proyecto habilitada para Identity Toolkit y Secure Token. Abrir una sesión local de confianza, con depuración HTTP desactivada, y restringir por permisos NTFS el acceso a `iot/build/provisioning` al operador antes de crear credenciales.
3. Desde la raíz del repositorio, aprovisionar tras autorizar el cambio en Firebase. El Admin SDK solicita la firma a IAM con ADC y canjea el token inmediatamente mediante `accounts:signInWithCustomToken`. Firebase puede crear el registro técnico del UID durante el canje. Solo se conserva el refresh token, el UID y la fecha de autenticación en el archivo local ignorado.

   ```powershell
   gcloud auth application-default login
   gcloud auth application-default set-quota-project voltify-e51f9
   npm install --prefix iot/build/provisioning --no-audit --no-fund firebase-admin@13.7.0
   $env:VOLTIFY_SIGNER_EMAIL = 'CUENTA_FIRMANTE@voltify-e51f9.iam.gserviceaccount.com'
   $env:VOLTIFY_CLIENT_API_KEY = 'API_KEY_CLIENTE'
   node iot/tools/provision-device.cjs --provision
   ```

   El archivo `iot/build/provisioning/device-session.local.json` se crea de forma exclusiva. Si un intento deja un archivo vacío, comprobar primero el UID en Auth y resolver el estado de la vinculación antes de apartar el archivo y repetir. No usar `GOOGLE_APPLICATION_CREDENTIALS` con una clave de servicio para este procedimiento.
4. En RTDB preparar `/iotDeviceBindings/voltify-01-nodemcu` con `enabled: false`, `ownerUid` del propietario, `artifactKey` como texto decimal, `slot` entero libre de 0 a 23, `gatewayComponentId: "voltify-01-nodemcu"` y `minAuthTime` numérico copiado del archivo local (segundos Unix). Usar el artefacto físico existente. Si se sustituye una vinculación anónima, deshabilitarla y conservar el slot del mismo artefacto para preservar la identidad de los bloques históricos.
5. Cargar los ocho documentos estáticos en Firestore conservando las colecciones existentes. Publicar las reglas revisadas de RTDB y Firestore solo después de la aprobación y las pruebas locales. Las reglas de telemetría y comandos exigen proveedor `custom`, UID fijo, vinculación habilitada y una sesión con `auth_time >= minAuthTime`.
6. Copiar `VoltifyConfig.example.h` a `VoltifyConfig.local.h` y completar Wi-Fi, API key, `DEVICE_REFRESH_TOKEN`, propietario, artefacto, slot y metadatos históricos. El UID está fijado en el sketch. Activar la nueva vinculación al iniciar la prueba autorizada. Mantener bajo control local el archivo privado, el encabezado y el binario resultante.

**Reinicio y revocación.** El token de arranque se conserva en el firmware local, por lo que un reinicio vuelve a solicitar un ID token. Los tokens devueltos por la renovación permanecen en RAM. Si Firebase invalida el token de arranque o una renovación lo reemplaza sin dejarlo válido, reprovisionar. La flash del ESP8266 carece de protección frente a extracción física. Ante pérdida o rotación, poner primero `enabled: false` en RTDB para cortar incluso sesiones con ID token vigente. Revocar los refresh tokens con `node iot/tools/provision-device.cjs --revoke` usando ADC de un cliente OAuth de escritorio propio (`gcloud auth application-default login --client-id-file RUTA_AL_CLIENTE_OAUTH_LOCAL`) y permisos de administración de Auth. También se puede deshabilitar el usuario técnico desde Firebase Authentication. La firma por IAM admite el ADC de gcloud del paso 3. [ADC local para operaciones de Auth](https://firebase.google.com/docs/admin/setup#test-with-gcloud-end-user-credentials) Para rotar el mismo UID, mantener la vinculación deshabilitada, revocar los refresh tokens anteriores, apartar de forma segura el archivo local previo y emitir una sesión nueva después de esperar al menos un segundo. Si el usuario técnico quedó deshabilitado en Auth, habilitarlo antes del canje y mantener todavía deshabilitada su vinculación en RTDB. Antes de reactivar, cambiar `minAuthTime` al valor nuevo, verificando que sea posterior al anterior, y preparar el firmware con el refresh token nuevo. Así, los ID tokens anteriores siguen excluidos tras reactivar la vinculación.

El firmware valida TLS con raíces GTS y sincroniza hora con SNTP. Conserva un bloque pendiente y descarta muestras caducadas. Durante cortes prolongados puede haber huecos. El sondeo de órdenes y las escrituras comparten una conexión HTTPS cuando el servidor permite reutilizarla. La latencia de TLS, la recepción UART y el heap real se verifican en placa. [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth/) · [Firma de tokens personalizados](https://firebase.google.com/docs/auth/admin/create-custom-tokens) · [Revocación de sesiones](https://firebase.google.com/docs/auth/admin/manage-sessions)

## Canal de retorno

En un artefacto físico monitoreado, **Dispositivos → Controles IoT** permite solicitar una lectura INA219 o confirmar una prueba individual de un LED o del buzzer. Android escribe una orden de 12 segundos en el buzón `users/{ownerUid}/iotCommands/{artifactKey}`. La orden contiene `id`, `targetComponentId` y `expiresAt`. La placa vinculada reserva la orden con `CLAIMED` y solo entonces envía la trama UART. Tras recibir confirmación CRC válida del Uno para ese ID, registra `EXECUTED` con su UID. Las reglas impiden reclamar dos veces, falsificar el resultado desde Android o confirmar una orden reemplazada o vencida.

Una reserva sin confirmación no se reenvía por seguridad. Un corte después de reservar puede dejar una orden sin confirmar, incluso si el Arduino llegó a recibirla. El operador puede reintentar con una orden nueva cuando venza. El ESP exige CRC de telemetría por defecto. Durante una transición controlada, la opción local `ALLOW_LEGACY_UART` admite el sketch antiguo. El nuevo Uno agrega CRC-16 a la telemetría y ambos sentidos de los comandos usan CRC-16. La prueba manual solo agrega un pulso nominal de 500 ms y conserva el estado automático de los LED y buzzer.

## Compilación y prueba física

Compilar con `--libraries iot/firmware/libraries`. Usar `arduino:avr:uno` para el sketch bidireccional y `esp8266:esp8266:nodemcuv2` para el firmware Firebase, con ArduinoJson 7.4.2 para ESP8266. También se pueden instalar las bibliotecas del directorio compartido en el sketchbook de Arduino IDE. El proyecto emulado se ejecuta desde la raíz con:

```powershell
npm install --prefix iot/build/provisioning --no-audit --no-fund firebase-admin@13.7.0
node iot/build/emulator/node_modules/firebase-tools/lib/bin/firebase.js --config firebase.iot-emulator.json --project demo-voltify-iot emulators:exec --only auth,database,firestore 'node iot/tests/rtdb.test.cjs'
```

Solo tras autorizar el despliegue físico:

1. Con la batería de prueba desconectada, revisar tierra común y el sentido de ambos canales del conversor. Confirmar en particular **NodeMCU TX D1 → Arduino RX D2** a través del canal adecuado del conversor. El trayecto previamente aprobado del Uno al NodeMCU no acredita el retorno. No conectar una salida de 5 V a un GPIO del ESP8266.
2. Con respaldo y autorización para ambos cambios, cargar el sketch bidireccional en COM3 y el de Firebase en COM4. Instalar Android mediante actualización de firma compatible. Activar el monitoreo del artefacto de prueba.
3. Confirmar el flujo INA219 → Android, la cadencia en vivo y una fila histórica por bloque. Solicitar una lectura y comprobar el nuevo dato y su confirmación. Probar cada salida individualmente y comprobar que una alerta automática elevada sigue activa durante una prueba manual.
4. En la misma sesión, hacer un corte breve de Wi-Fi y verificar recuperación, expiración de una orden pendiente, ausencia de repeticiones y de bloques históricos duplicados. Registrar latencia y heap libre durante HTTPS. Al terminar, detener el monitoreo.

[Identificación e inventario](COMPONENTES.md)
