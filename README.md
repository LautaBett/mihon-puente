# Puente Mihon

App de Mac y app de Android que juntan los `.tachibk` de Mihon y de Tachimanga en una sola biblioteca. Supabase guarda esa copia. Cada app incorpora lo que mejora la actual y deja `salida/puente-importar.tachibk` para importar en la otra.

El diseño está en `docs/`:

- [Arquitectura](docs/arquitectura.md)
- [Flujos](docs/flujos.md)
- [Casos de uso](docs/casos-de-uso.md)
- [Reglas de fusión](docs/reglas-de-fusion.md)

El esquema está en `supabase/schema.sql`. Se pega en el SQL Editor. Hace falta un usuario de Authentication y, en las dos apps, la misma URL, la clave publicable, el mail y la contraseña. La clave `service_role` no se usa.

## Mac

```bash
./gradlew :desktopApp:run
```

Elegí la carpeta donde exportás el backup compatible con Tachiyomi. Con la URL vacía, la copia queda en `~/.mihon-puente/local/` y no sale de la máquina. La ventana revisa la carpeta cada 15 segundos.

Para fusionar dos archivos sin abrir la ventana:

```bash
./gradlew :desktopApp:run --args="merge canonico.tachibk entrante.tachibk salida.tachibk"
```

El primero es la copia actual y el segundo el backup nuevo. La fecha sale del nombre del segundo.

## Android

En esta Mac no hay Android SDK, así que `:androidApp` entra al build recién cuando exista `ANDROID_HOME` o `local.properties` con `sdk.dir`. Con el SDK instalado:

```bash
./gradlew :androidApp:installDebug
```

La app pide la carpeta `autobackup` de Mihon, revisa mientras está abierta y el botón Abrir en Mihon manda el `.tachibk` a la pantalla de restaurar.

## Qué ya hace esta versión

- Abre y escribe `.tachibk` con el protobuf de Mihon.
- Fusiona progreso, historial, categorías y ediciones con las reglas del doc.
- No ofrece importar si el backup local va más adelante.
- Guarda la copia en disco o en Supabase, con versión para cuando las dos apps escriben a la vez.

Importar dentro de Mihon o Tachimanga sigue siendo un paso tuyo. Tachimanga no genera solo el backup compatible con Tachiyomi.
