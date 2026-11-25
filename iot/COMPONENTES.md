# Identificación del prototipo Voltify

Etiqueta cada elemento con el ID lógico de la tabla. El prefijo `voltify-01` identifica este montaje. Conserva el mismo ID al cambiar el nombre, el cableado, el firmware o las credenciales. Para otro montaje, asigna un prefijo distinto. Registra los ocho componentes en Cloud Firestore, `users/{ownerUid}/iotComponents/{componentId}` con la plantilla [iotComponents.example.json](iotComponents.example.json). La clave de cada registro coincide con `componentId` y todos comparten `prototypeId`.

| ID lógico | Componente | Conexión de referencia |
|---|---|---|
| `voltify-01-arduino-uno` | Arduino Uno R3 | UART a 9600 bps, TX D3 hacia RX D2 del NodeMCU por conversor de nivel |
| `voltify-01-nodemcu` | NodeMCU ESP8266EX | UART RX D2 desde Arduino. TX D1 está definido en el código y su trayecto hacia RX D2 del Uno debe confirmarse para una futura comunicación en sentido inverso |
| `voltify-01-ina219` | INA219 | I²C, SDA A4 y SCL A5 del Uno. Dirección por defecto `0x40` (64) con la configuración del sketch de revisión |
| `voltify-01-lcd` | LCD I²C 16×2 | I²C en el mismo bus. Dirección configurada `0x27` (39) |
| `voltify-01-led-verde` | LED verde | GPIO D4 del Uno |
| `voltify-01-led-amarillo` | LED amarillo | GPIO D5 del Uno |
| `voltify-01-led-rojo` | LED rojo | GPIO D6 del Uno |
| `voltify-01-buzzer` | Buzzer | GPIO D7 del Uno |

Los pines y direcciones describen los sketches de revisión. Las direcciones I²C se guardan en decimal en el registro y dependen de la configuración del módulo.

## Identidades y vínculos

- `componentId` identifica un componente físico de forma lógica. `prototypeId` agrupa esos componentes; ambos quedan persistidos en el inventario.
- `ownerUid` es el UID de Firebase Authentication del propietario y delimita su espacio de datos.
- `deviceUid` es el UID de la única cuenta dedicada al gateway. `/iotDeviceBindings/{deviceUid}` autoriza al NodeMCU para un propietario y un artefacto concretos. Al aprovisionar, registra también `gatewayComponentId: "voltify-01-nodemcu"` en esa vinculación como referencia administrativa. Los permisos de telemetría siguen evaluando `enabled`, `ownerUid`, `artifactKey` y, para el historial, `slot`.
- `artifactKey` referencia el registro existente de `users/{ownerUid}/artifacts/{artifactKey}`. Su `id` numérico identifica el artefacto monitoreado en los consumos. La vinculación del gateway asocia el montaje con ese artefacto.
- `0x40` y `0x27` son direcciones de bus I²C. Otros montajes pueden usar las mismas direcciones.

El registro guarda metadatos y conexiones. Reutiliza el artefacto, `iotLive` y `consumptions` existentes. Un usuario autenticado con correo verificado puede leer y administrar su propio inventario. La cuenta dedicada al NodeMCU conserva sus permisos actuales de telemetría y el acceso al inventario queda reservado al propietario.

### Inventario complementario

Registra en la ficha de montaje el conversor de nivel lógico UART de 5 V a 3,3 V (`voltify-01-level-shifter`), la batería de 9 V (`voltify-01-bateria`), la carga de 100 Ω / 5 W (`voltify-01-carga`), la protoboard (`voltify-01-protoboard`) y el juego de cables (`voltify-01-cableado`) si están presentes. Para las resistencias limitadoras instaladas, usa `voltify-01-res-led-verde`, `voltify-01-res-led-amarillo` y `voltify-01-res-led-rojo` según corresponda. Estos elementos pueden etiquetarse localmente como parte de `voltify-01`. El montaje usa una sola cuenta técnica de Firebase, la del gateway.

### Asociación de comandos (pasos 3 y 4)

La orden de Android incluye `targetComponentId` con el ID exacto del LED o buzzer de la tabla. La vinculación administrativa resuelve el gateway del mismo montaje. El NodeMCU acepta únicamente los cuatro IDs de actuadores asignados a su montaje y transmite el ID de la orden y una operación de la tabla por UART. El Arduino selecciona la salida D4, D5, D6 o D7 mediante una tabla local fija. Cada orden contiene un ID único, caduca y requiere confirmación del Arduino. Las pruebas manuales conservan las alertas locales. Los pines quedan como metadatos del inventario.

El canal de comandos se prueba de forma local. La actuación remota y el retorno UART quedan pendientes de verificación física.

### Aprovisionamiento pendiente

Tras autorizar el despliegue, comparar y publicar las reglas revisadas. Crear cada entrada de la plantilla como documento de Firestore bajo `iotComponents/{componentId}`, mediante un cliente autenticado del propietario o en Firebase Console con acceso administrativo. Usar exclusivamente el UID propietario correcto y conservar los hijos existentes. Etiquetar el montaje y añadir `gatewayComponentId` a la vinculación que se cree para la cuenta dedicada. El inventario puede aprovisionarse con los firmwares actuales.
