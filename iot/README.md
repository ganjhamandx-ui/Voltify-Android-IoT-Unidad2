# Voltify IoT físico · Unidades 3 y 4

Arduino Uno R3 e INA219 → UART a 9600 → NodeMCU ESP8266 → HTTPS → Firebase RTDB → Android `cl.voltify.app`. Los sketches de revisión se conservan como referencia. El firmware bidireccional del Uno está en `firmware/arduino/Voltify_Arduino_Bidirectional`.

## Distribución de datos

- **Firebase Auth** identifica a los usuarios Android mediante el correo verificado. La identidad técnica del NodeMCU usa el proveedor anónimo y una vinculación administrativa, con una credencial renovable exclusiva de la placa.
- **Cloud Firestore** conserva perfiles y documentos de dispositivos en las rutas existentes. Los ocho componentes se aprovisionan de la plantilla en `users/{ownerUid}/iotComponents/{componentId}`. Android consulta Firestore antes de enviar órdenes a esos destinos. El cliente y las reglas nuevas ya no usan la versión anterior del inventario en RTDB. Ante datos heredados, revisar y respaldar antes de migrarlos. Este cambio no borra registros.
- **Realtime Database** conserva `users/{ownerUid}/iotLive/{artifactKey}` (última lectura, objetivo de 2 s) y `users/{ownerUid}/consumptions/{id}` (bloques de 30 s). El bloque contiene solo el incremento plausible de energía y recibe un ID fijo por slot y bloque. Las lecturas físicas siguen marcadas `IOT_REAL`. Se preservan las operaciones `SIMULADA`.

## Preparación de Firebase · requiere autorización

1. En `voltify-e51f9`, respaldar y comparar las reglas publicadas, revisar permisos heredados y comprobar si App Check para RTDB exige atestación al cliente REST. Conservar el acceso de Android. No deshabilitar App Check para sortear restricciones.
2. Habilitar el proveedor **Anónimo** en Firebase Authentication. En proyectos con Identity Platform, revisar la limpieza automática de cuentas anónimas de más de 30 días antes de usar esta identidad como persistente.
3. Crear **una sola vez** la identidad técnica, fuera del firmware y sin correo, mediante el endpoint documentado signUp con solo `returnSecureToken: true`. Ejemplo de PowerShell desde la raíz, tras autorizar esa escritura. Definir `VOLTIFY_CLIENT_API_KEY` en la sesión local con una clave cliente del mismo proyecto que permita Identity Toolkit y Secure Token.

   ```powershell
   New-Item -ItemType Directory -Force iot/build | Out-Null
   $voltifyPrivateFile = Join-Path (Get-Location) 'iot/build/anonymous-device.local.json'
   if (Test-Path -LiteralPath $voltifyPrivateFile) { throw 'Ya hay credenciales locales; no crees otra identidad.' }
   $voltifyEndpoint = 'https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=' + $env:VOLTIFY_CLIENT_API_KEY
   Invoke-RestMethod -Method Post -Uri $voltifyEndpoint -ContentType 'application/json' -Body '{"returnSecureToken":true}' |
       Select-Object localId,refreshToken |
       ConvertTo-Json -Compress |
       Set-Content -LiteralPath $voltifyPrivateFile -Encoding utf8
   ```

4. En RTDB agregar `/iotDeviceBindings/{deviceUid}`, donde `deviceUid` es `localId`. Configurar `enabled: true`, `ownerUid` del propietario, `artifactKey` como texto decimal, `slot` entero libre de 0 a 23 y `gatewayComponentId: "voltify-01-nodemcu"`. Asignar una sola vinculación activa por gateway/artefacto. Usar un artefacto activo con fuente `IOT_REAL`. Conservar el slot histórico para el mismo artefacto al rotar la credencial y no reutilizarlo para otro artefacto.
5. Cargar los ocho documentos estáticos en Firestore sin sustituir las colecciones existentes. Publicar las reglas revisadas de Firestore y RTDB únicamente después de aprobarlas y probarlas con el emulador. Los clientes anónimos sin vinculación carecen de permisos de telemetría y comandos.
6. Copiar `VoltifyConfig.example.h` a `VoltifyConfig.local.h` y completar Wi-Fi, API key, `DEVICE_UID` con `localId`, `DEVICE_REFRESH_TOKEN`, propietario, artefacto, slot y metadatos históricos. Restringir el acceso al archivo privado y al binario. El token de arranque persiste en el firmware y los tokens renovados se conservan en RAM. Si Firebase invalida o sustituye el token de arranque, se requiere reprovisión. Para revocar, deshabilitar primero la vinculación y después deshabilitar o eliminar la identidad técnica. Nunca distribuir los archivos con credenciales.

El firmware valida TLS con raíces GTS y sincroniza hora con SNTP. Conserva un bloque pendiente y descarta muestras caducadas. Durante cortes prolongados puede haber huecos. El sondeo de órdenes y las escrituras comparten una conexión HTTPS cuando el servidor permite reutilizarla. La latencia de TLS, la recepción UART y el heap real se verifican en placa. [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth/) · [Cuentas anónimas y limpieza automática](https://firebase.google.com/docs/auth/android/anonymous-auth)

## Canal de retorno

En un artefacto físico monitoreado, **Dispositivos → Controles IoT** permite solicitar una lectura INA219 o confirmar una prueba individual de un LED o del buzzer. Android escribe una orden de 12 segundos en el buzón `users/{ownerUid}/iotCommands/{artifactKey}`. La orden contiene `id`, `targetComponentId` y `expiresAt`. La placa vinculada reserva la orden con `CLAIMED` y solo entonces envía la trama UART. Tras recibir confirmación CRC válida del Uno para ese ID, registra `EXECUTED` con su UID. Las reglas impiden reclamar dos veces, falsificar el resultado desde Android o confirmar una orden reemplazada o vencida.

Una reserva sin confirmación no se reenvía por seguridad. Un corte después de reservar puede dejar una orden sin confirmar, incluso si el Arduino llegó a recibirla. El operador puede reintentar con una orden nueva cuando venza. El ESP exige CRC de telemetría por defecto. Durante una transición controlada, la opción local `ALLOW_LEGACY_UART` admite el sketch antiguo. El nuevo Uno agrega CRC-16 a la telemetría y ambos sentidos de los comandos usan CRC-16. La prueba manual solo agrega un pulso nominal de 500 ms y conserva el estado automático de los LED y buzzer.

## Compilación y prueba física

Compilar con `--libraries iot/firmware/libraries`. Usar `arduino:avr:uno` para el sketch bidireccional y `esp8266:esp8266:nodemcuv2` para el firmware Firebase, con ArduinoJson 7.4.2 para ESP8266. También se pueden instalar las bibliotecas del directorio compartido en el sketchbook de Arduino IDE. El proyecto emulado se ejecuta desde la raíz con:

```powershell
node iot/build/emulator/node_modules/firebase-tools/lib/bin/firebase.js --config firebase.iot-emulator.json --project demo-voltify-iot emulators:exec --only auth,database,firestore 'node iot/tests/rtdb.test.cjs'
```

Solo tras autorizar el despliegue físico:

1. Con la batería de prueba desconectada, revisar tierra común y el sentido de ambos canales del conversor. Confirmar en particular **NodeMCU TX D1 → Arduino RX D2** a través del canal adecuado del conversor. El trayecto previamente aprobado del Uno al NodeMCU no acredita el retorno. No conectar una salida de 5 V a un GPIO del ESP8266.
2. Con respaldo y autorización para ambos cambios, cargar el sketch bidireccional en COM3 y el de Firebase en COM4. Instalar Android mediante actualización de firma compatible. Activar el monitoreo del artefacto de prueba.
3. Confirmar el flujo INA219 → Android, la cadencia en vivo y una fila histórica por bloque. Solicitar una lectura y comprobar el nuevo dato y su confirmación. Probar cada salida individualmente y comprobar que una alerta automática elevada sigue activa durante una prueba manual.
4. En la misma sesión, hacer un corte breve de Wi-Fi y verificar recuperación, expiración de una orden pendiente, ausencia de repeticiones y de bloques históricos duplicados. Registrar latencia y heap libre durante HTTPS. Al terminar, detener el monitoreo.

[Identificación e inventario](COMPONENTES.md)
