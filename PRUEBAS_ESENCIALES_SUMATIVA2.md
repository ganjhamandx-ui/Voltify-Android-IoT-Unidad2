# Lista corta de regresión — V12.3

Estas acciones NO sustituyen las evidencias de la V12.2 ya probada. Son las mínimas para autorizar la instalación de un código nuevo:

1. **Build**: Android Studio compila sin errores. ADB indica `versionName=12.3`, `versionCode=15` y `cl.voltify.app`.
2. **Login**: correo/contraseña reales ingresan; contraseña incorrecta muestra mensaje genérico. Registro mantiene política fuerte.
3. **Datos existentes**: TV y lámpara originales, y su historial, permanecen en Firebase y ambos Android con el mismo UID; NO reinicializar RTDB.
4. **Sincronización**: activar TV en uno y ver `MONITOREANDO` en el otro. Se visualiza potencia dinámica; Firebase recibe un consumo posterior. Detenerlo.
5. **Concurrencia**: activar TV + lámpara en ambos; comparar una misma fila agrupada por artefacto/bloque, sin sumar dos observadores; revisar costos e historial.
6. **Funciones secundarias**: abrir Historial, Metas, Consejos, Reportes, Configuración; comprobar navegación y ausencia de cierres.
7. **Modo avión**: con ambos monitoreos detenidos, la app permanece abierta y al restablecer la conexión conserva artefactos.
8. **Iconos**: `pm list packages | findstr voltify` devuelve solo `cl.voltify.app` después de limpiar el identificador viejo del emulador.

**No desinstalar cl.voltify.app del Samsung** ni borrar datos de Firebase. Conservar copia del ZIP V12.2 como reversión. Antes de publicar nuevas reglas RTDB, exportar la base y respaldar las reglas vigentes.
