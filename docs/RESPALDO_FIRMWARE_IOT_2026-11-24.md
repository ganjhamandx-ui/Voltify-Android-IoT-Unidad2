# Voltify IoT — Registro de respaldo del prototipo físico (24-11-2026)

> Documento de recuperación. **Los archivos de firmware NO están alojados en este repositorio público.** Conservarlos en almacenamiento privado, preferentemente cifrado. El firmware del ESP8266 puede contener credenciales o parámetros sensibles.

## Placas y respaldos confirmados

| Placa | Puerto observado (HP) | Archivo de respaldo local | Tamaño |
| --- | --- | --- | --- |
| Arduino Uno R3 (ATmega328P, USB CH340) | COM3 | `C:\Voltify_HP\Respaldo_Arduino_Original.hex` | 78.861 bytes (archivo Intel HEX) |
| NodeMCU ESP8266EX (USB CH340, Flash 4 MB) | COM4 | `C:\Voltify_HP\Respaldo_NodeMCU_Original.bin` | 4.194.304 bytes (4 MiB) |

Los dos archivos se obtuvieron **por lectura** de la memoria de sus placas (sin reprogramar). Se verificaron existencia y tamaños en la HP. No equivalen al código fuente original `.ino`.

### Herramientas empleadas

- Arduino IDE 2.3.11.
- AVRDUDE `8.0.0-arduino1`: lectura de `atmega328p`, `arduino`, `COM3`, 115200 baudios; memoria flash en Intel HEX.
- ESPTool `v3.0` del paquete ESP8266 Arduino `3.1.2`: lectura de `COM4` desde `0x0` por `0x400000` bytes a archivo BIN.
- Puerto NodeMCU identificado como `NodeMCU 1.0 (ESP-12E Module)`; Monitor Serial a 9600 baudios.

## Montaje acreditado

- Arduino Uno R3 con INA219, pantalla LCD I²C, tres LED y buzzer.
- Batería de 9 V y carga de prueba: **desconectadas** durante el diagnóstico. Lecturas de cero no significan avería cuando no hay carga.
- Comunicación Arduino → NodeMCU por serial observada en el monitor de COM4 a 9600 baudios: mensajes repetidos `Arduino dice: V=0.00;I=0.00;P=0.000;POR=0.0;EST=NORMAL;E=0.000000;C=0.000`.
- Esto **confirma la recepción serial entre ambas placas durante esa prueba**; no demuestra conexión Wi-Fi ni envío de datos físicos a Firebase.

## Formato de comunicación identificado en el firmware del Arduino

```text
V=<valor>;I=<valor>;P=<valor>;POR=<valor>;EST=<estado>;E=<valor>;C=<valor>
```

Los literales `V=`, `;I=`, `;P=`, `;POR=`, `;EST=`, `;E=` y `;C=` se encontraron en el respaldo de Arduino. En el del NodeMCU se hallaron los textos `VOLTIFY - NodeMCU`, `Esperando datos del Arduino...` y `Arduino dice:`.

La interpretación esperada es voltaje, corriente, potencia, porcentaje, estado, energía y costo. Las unidades y escalas precisas requieren cotejo con el programa fuente o una medición controlada.

## Limitaciones y recuperación

1. Conservar **los archivos originales** en al menos dos ubicaciones seguras y privadas, registrar hashes SHA-256 y fecha.
2. **No subir BIN/HEX sin protección a este repositorio público**; el contenido del ESP8266 podría incluir secretos. Tampoco publicar contraseñas o capturas de credenciales.
3. El respaldo compilado **no recupera automáticamente el sketch `.ino`** con comentarios y nombres originales.
4. **No flashear o restaurar** antes de documentar placa, parámetros y cableado; una restauración sobrescribiría el firmware actual.
5. Estado pendiente: NodeMCU → Wi-Fi → Firebase Realtime Database → app Android. La app móvil y sus sincronizaciones existentes no deben confundirse con datos del sensor físico.

Este registro tiene como objetivo conservar el diagnóstico y facilitar la recuperación del montaje sin repetir pruebas ya acreditadas.
