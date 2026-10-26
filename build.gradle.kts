// Las versiones de los plugins están en cada módulo. Android y Kotlin tienen
// que compartir el classpath del buildscript; si entran por el bloque plugins
// de la raíz, Kotlin no llega a ver las clases de Android Gradle Plugin 8.
