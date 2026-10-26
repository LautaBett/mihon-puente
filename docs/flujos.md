# Flujos

## Incorporar un backup

1. La app encuentra un `.tachibk` cuyo SHA-256 no incorporó antes.
2. Lo abre (gzip y después protobuf). Si no se puede leer, lo anota como fallido y no reintenta en cada pasada.
3. Toma la fecha del nombre. Si el nombre no trae fecha, usa la fecha de modificación del archivo.
4. Baja la copia canónica y `clocks.json`. Si todavía no hay nada, arranca de vacío.
5. Fusiona con las reglas de `docs/reglas-de-fusion.md`.
6. Si la biblioteca y los relojes quedaron iguales, no sube nada.
7. Si algo cambió, sube los archivos y llama a `advance_head` con la versión que había leído.
8. Si la versión ya no coincide, vuelve al paso 4 hasta tres veces.
9. Recién ahí marca el SHA como incorporado. Si el proceso se corta antes, el mismo archivo se vuelve a intentar.

Incorporar dos veces el mismo archivo no genera otra versión. La fusión es idempotente.

## Ofrecer la importación

1. Elige el backup local más nuevo (fecha del nombre, si no la de modificación).
2. Si ese archivo todavía no se incorporó, no ofrece importar.
3. Compara ese backup con la copia canónica, capítulo por capítulo.
4. Si en algún capítulo el archivo local va más adelante (leído, página, fecha de historial o tiempo de lectura) o le falta una serie a la copia canónica, no escribe el archivo de importación y lo dice en el registro.
5. Si la copia canónica es igual o va adelante, y su SHA todavía no se ofreció, escribe `salida/puente-importar.tachibk`.
6. La pasada siguiente ve el mismo SHA y no vuelve a avisarlo. Un cambio nuevo de la biblioteca sí avisa.

## Pasada automática

Cada 15 segundos, mientras la app está abierta:

1. Lee la carpeta.
2. Incorpora lo pendiente, de viejo a nuevo.
3. Evalúa la importación contra el backup local más nuevo.
4. Escribe el archivo de salida solo cuando hay una versión nueva y segura.

En la Mac la ventana puede quedar abierta. En Android la revisión corre mientras la actividad está en primer plano, y también con el botón Revisar.

## Primer uso

1. Aplicás `supabase/schema.sql` en el SQL Editor del proyecto.
2. Creás un usuario en Authentication. Si el proyecto exige confirmar el mail, crealo desde el dashboard para que ya quede confirmado.
3. En las dos apps ponés la misma URL, la clave publicable (anon key), el mail y la contraseña.
4. En Android elegís la carpeta `autobackup` de Mihon. En la Mac, la carpeta donde vas a dejar los backups de Tachimanga.
5. Activás el backup automático de Mihon. En Tachimanga exportás un backup compatible con Tachiyomi hacia esa carpeta.
6. Cuando el registro diga que hay un archivo en `salida/`, lo importás en la otra app.

La contraseña queda en `~/.mihon-puente/config.json` en la Mac y en las preferencias de la app en Android. No va al repositorio. La clave que se pega en la app es la publicable. La clave `service_role` no se usa.

## Cambio de modo local a Supabase

Si la URL está vacía, la copia canónica queda en `~/.mihon-puente/local/` (Mac) o en el almacenamiento privado de la app (Android). Esa copia local no se sube sola al conectar Supabase: la primera pasada con URL usa lo que haya en el servidor. Conviene poner Supabase antes de incorporar el primer backup, o volver a exportar un backup después de conectarlo.
