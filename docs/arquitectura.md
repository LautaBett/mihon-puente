# Arquitectura

Puente es un par de apps, una en Android y otra en la Mac, que mantienen una sola biblioteca compartida entre Mihon y Tachimanga. Ninguna de esas dos apps habla con Supabase. El puente lee los `.tachibk` que ellas exportan, fusiona solo lo que mejora la copia actual y deja un archivo listo para importar.

## Piezas

```
Mihon
  autobackup/*.tachibk
        │
        ▼
  app Android ──┐
                │  HTTPS (clave publicable + sesión del usuario)
                ▼
           Supabase
           library_head     una fila por usuario, con número de versión
           storage/puente   library.tachibk + clocks.json
           devices          qué aparato escribió
                ▲
                │
  app Mac ──────┘
        ▲
        │
Tachimanga
  carpeta donde exportás el backup compatible con Tachiyomi
```

La fusión vive en el módulo `core`, un jar JVM. La app de Mac y la de Android lo usan tal cual. Así las dos deciden igual. Android ejecuta bytecode JVM, así que no hace falta un segundo lenguaje ni una segunda implementación.

## Qué guarda Supabase

- `library.tachibk`: la biblioteca ya fusionada, en el mismo formato que Mihon escribe (gzip + protobuf de kotlinx.serialization). Sin ajustes de la app, sin ajustes de fuentes y sin repos de extensiones.
- `clocks.json`: la fecha de la última edición ganadora de cada serie, capítulo, categoría y fuente. Hace falta porque el `.tachibk` no trae fecha para notas, categorías ni marcadores.
- `library_head.state_version`: sube en cada cambio. El otro aparato se entera por ese número.
- `library_head.library_generation`: sube solo cuando cambia el `.tachibk`. Si solo se movieron los relojes, no hace falta importar de nuevo.

La fecha que manda es la del backup (la del nombre `aaaa-mm-dd_hh-mm`, leída en la zona horaria del aparato que incorpora el archivo), no la hora de subida. Un archivo viejo que llega tarde no pisa una edición más nueva.

Si las dos apps guardan a la vez, `advance_head` acepta el guardado solo cuando `state_version` sigue siendo el que la app leyó. La otra vuelve a bajar la copia, fusiona de nuevo y reintenta.

## Qué hace cada app

1. Lista los `.tachibk` de la carpeta. Ignora `salida/` y todo nombre que empiece con `puente-`, que son archivos escritos por el propio puente.
2. Incorpora los que todavía no vio, del más viejo al más nuevo.
3. Si la copia compartida quedó estrictamente adelante del backup local más reciente, escribe `salida/puente-importar.tachibk` una sola vez por versión.
4. Importar ese archivo sigue siendo un paso dentro de Mihon o Tachimanga. En Android, el botón abre el archivo y Mihon muestra la pantalla de restaurar (`ACTION_VIEW` de un uri que termina en `.tachibk`). En la Mac, el botón abre la carpeta `salida`.

## Límites que no se cruzan

- El puente no escribe la base interna de Mihon ni la de Tachimanga.
- No manda capítulos descargados. El formato no los trae.
- No manda ajustes ni tokens de trackers. Cada aparato se queda con los suyos.
- La comprobación de seguridad compara contra el último archivo de backup, no contra lo que estás leyendo ahora mismo y todavía no se exportó. Importar en medio de una lectura que no llegó a un backup puede pisar esa lectura.
- Tachimanga no genera solo el backup compatible con Tachiyomi. Hasta que lo exportes a la carpeta vigilada, la Mac no tiene nada que enviar. Mihon sí, con el backup automático.

## Dónde vive cada cosa en el repo

| Ruta | Qué es |
|---|---|
| `core/` | Codec, fusión, seguridad de importación, cliente de Supabase |
| `desktopApp/` | App de Mac |
| `androidApp/` | App de Android. Entra al build cuando hay un Android SDK |
| `supabase/schema.sql` | Tablas, RLS y función de guardado |
| `docs/` | Flujos, casos de uso y reglas |
