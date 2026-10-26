# Reglas de fusión

La identidad de una serie es `source` + `url`. La de un capítulo es la `url` del capítulo dentro de esa serie. La de una categoría es el nombre.

Hay dos clases de datos.

## Progreso, que solo puede ir para adelante

Se aplica aunque el archivo sea más viejo que la copia actual.

| Dato | Resultado |
|---|---|
| Capítulo leído | Leído si cualquiera de los dos lo marcó |
| Última página | La mayor. Si el capítulo ya está leído, la página no bloquea una importación |
| Historial `lastRead` | La fecha más nueva |
| Tiempo de lectura | El mayor. Sumar duplicaría el total, porque cada backup trae el acumulado |
| `dateAdded` | La más antigua que no sea cero |
| `initialized` | Verdadero si cualquiera de los dos lo tiene |

## Ediciones, que pueden volver atrás

Las aplica el backup más nuevo. La fecha es la del nombre del archivo. Para “en biblioteca”, si los dos traen `favoriteModifiedAt`, gana esa marca (está en segundos en los backups viejos y el puente lo normaliza a milisegundos).

| Dato | Resultado |
|---|---|
| En biblioteca | El `favoriteModifiedAt` más nuevo, y si no hay marca, el archivo más nuevo |
| Notas, visor, flags, estrategia de actualización, scanlators excluidos, ficha (título, autor, estado, portada) | El archivo más nuevo. Un texto vacío o un estado 0 no borra el valor actual: en el protobuf no se distingue “lo borré” de “este backup no lo trae” |
| Categorías de la serie | La lista del archivo más nuevo, con los ids traducidos al id canónico de cada nombre |
| Marcador | El archivo más nuevo |
| Metadatos del capítulo (nombre, número, grupo, fechas) | El archivo más nuevo. El progreso se superpone después, así un archivo nuevo no borra un “leído” |
| Tracking del mismo `syncId` | Campos del archivo más nuevo, y `lastChapterRead` el máximo |
| Definición de categoría (orden y flags) | El archivo más nuevo, conservando el id canónico |
| Nombre de la fuente | El archivo más nuevo, si no viene vacío |

## Altas y ausencias

- Serie o capítulo que solo está en el archivo nuevo: se agrega.
- Serie o capítulo que el archivo no trae: se conserva.
- Categorías: se unen por nombre. Si el id entrante choca con otro nombre, se asigna un id libre.

## Qué no entra a la copia compartida

Ajustes de la app, ajustes por fuente y repos de extensiones. Al guardar, esas listas quedan vacías. Mihon, al restaurar, aplica las preferencias que vienen en el archivo. Una lista vacía no reescribe las del aparato. Tachimanga, al importar un backup de Tachiyomi, usa la biblioteca. Los ajustes de cada app se quedan donde están.

## Importación segura

Se compara el backup local más nuevo contra la copia canónica.

- Si falta una serie local en la copia canónica, no se importa.
- Si un capítulo local está leído y en la copia no, no se importa.
- Si la página local es mayor y el capítulo no figura leído en la copia, no se importa.
- Si `lastRead` o el tiempo de lectura local son mayores, no se importa.
- Un marcador distinto no bloquea. Es una edición, y la gana el archivo más nuevo.
- Series que solo están en la copia canónica no bloquean: la importación las agrega.

Mihon, al restaurar encima de una biblioteca existente, conserva el progreso más avanzado y la fecha de alta más antigua. Aun así el archivo que entrega el puente ya trae ese máximo, porque Tachimanga puede reemplazar el estado por lo que dice el archivo.
