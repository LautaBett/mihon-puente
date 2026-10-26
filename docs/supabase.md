# Supabase

El archivo para aplicar es `supabase/schema.sql`. Crea la fila de la copia, el registro de aparatos, la función `advance_head` y el bucket privado `puente`.

Cada tabla tiene RLS: un usuario solo ve sus filas. El bucket solo deja leer y escribir objetos cuya primera carpeta es el id de ese usuario. Para reemplazar un archivo hacen falta las políticas de lectura, alta y actualización. Están las tres.

`advance_head` corre como el usuario (`security invoker`), así que el RLS sigue valiendo. Actualiza la fila solo si `state_version` es la que la app leyó. Si no, no devuelve nada y la app fusiona de nuevo.

En las apps va la clave publicable del proyecto, la que en el dashboard figura como anon key. La sesión es mail y contraseña de un usuario creado en Authentication. Si el proyecto pide confirmar el mail, el usuario creado desde el dashboard ya queda confirmado.

La contraseña queda en `~/.mihon-puente/config.json` en la Mac y en el almacenamiento privado de la app en Android.
