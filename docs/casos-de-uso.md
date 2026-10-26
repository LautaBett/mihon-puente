# Casos de uso

## UC1. Leer en el teléfono y seguir en la Mac

Mihon escribe un backup automático. La app de Android lo incorpora. La de Mac, en la pasada siguiente, escribe `salida/puente-importar.tachibk`. Importás ese archivo en Tachimanga. El capítulo que marcaste leído en el teléfono queda leído. Los capítulos que ya estaban leídos en la Mac siguen leídos, aunque el backup del teléfono no los tuviera.

## UC2. Leer en la Mac y seguir en el teléfono

Exportás desde Tachimanga un backup compatible con Tachiyomi a la carpeta vigilada. La app de Mac lo incorpora. La de Android escribe el archivo de salida y el botón Abrir en Mihon muestra la pantalla de restaurar. Confirmás. El progreso de la Mac entra en Mihon y lo que ya habías leído en el teléfono se conserva.

## UC3. Las dos bibliotecas se exportan cerca en el tiempo

Cada archivo se fusiona por fecha de backup. El progreso de lectura se queda con lo más avanzado de los dos, aunque el archivo que lo trae sea el más viejo. Notas, categorías, marcadores y “en biblioteca” salen del archivo más nuevo. Si los dos guardan a la vez, uno de los dos recibe conflicto, vuelve a fusionar sobre la versión ganadora y el resultado final tiene las dos lecturas.

## UC4. Sacar una serie de la biblioteca

Si el backup más nuevo trae esa serie con `favorite = false` y su `favoriteModifiedAt` es posterior, la copia canónica la deja fuera de la biblioteca y conserva el historial. Si el backup simplemente deja de traerla, la copia canónica la conserva. Un backup no es una lista de borrados: si lo fuera, un archivo parcial vaciaría la biblioteca.

## UC5. La misma categoría con otro id

En el teléfono “Leyendo” puede ser el id 3 y en la Mac el id 7. La fusión une por nombre, se queda con un id y reescribe las referencias de las series. No queda la categoría duplicada.

## UC6. Importar sería un retroceso

Leíste un capítulo en el teléfono y el backup automático todavía no se escribió. La copia canónica, armada con un backup anterior, no tiene esa lectura. La app no escribe el archivo de importación y el registro dice que este aparato va adelante. Cuando el backup nuevo entra, la copia canónica alcanza al teléfono y recién ahí se ofrece la importación.

## UC7. Rebote después de importar

Importás el archivo del puente. La app, al rato, exporta un backup que ya contiene esa fusión. El puente lo abre, no encuentra nada mejor y no sube otra versión. Por eso no se persiguen en círculo.

## UC8. Archivo dañado o a medio escribir

Un archivo vacío o que no abre se anota una vez y la pasada siguiente lo saltea. Un archivo modificado hace menos de dos segundos se deja para la pasada siguiente, por si todavía se está escribiendo.

## UC9. Sin Supabase, en una sola máquina

Con la URL vacía, la app fusiona igual contra una copia local. Sirve para probar dos `.tachibk` en la Mac antes de crear el proyecto. Las dos apps no se ven entre sí hasta que apuntan al mismo proyecto y al mismo usuario.
