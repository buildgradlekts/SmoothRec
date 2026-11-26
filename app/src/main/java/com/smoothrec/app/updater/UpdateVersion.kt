package com.smoothrec.app.updater

/**
 * Comparación de versiones sin depender de Android, para poder probarla y para
 * evitar el error clásico de convertir "v0.4.0" en un versionCode inventado.
 */
object UpdateVersion {

    data class Semantic(val parts: List<Int>) {
        override fun toString(): String = parts.joinToString(".")
    }

    fun parseSemantic(raw: String?): Semantic? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""\d+(?:\.\d+){0,3}""").find(raw) ?: return null
        val parts = match.value.split(".").mapNotNull { it.toIntOrNull() }
        return if (parts.isEmpty()) null else Semantic(parts)
    }

    /** -1 si no se puede comparar, 1 si [a] es mayor, 0 si son iguales. */
    fun compare(a: Semantic, b: Semantic): Int {
        val size = maxOf(a.parts.size, b.parts.size)
        for (i in 0 until size) {
            val left = a.parts.getOrElse(i) { 0 }
            val right = b.parts.getOrElse(i) { 0 }
            if (left != right) return if (left > right) 1 else -1
        }
        return 0
    }

    fun isNewer(releaseTag: String?, installedName: String?): Boolean {
        val release = parseSemantic(releaseTag) ?: return false
        val installed = parseSemantic(installedName) ?: return true
        return compare(release, installed) > 0
    }

    /**
     * Lee un versionCode REAL publicado por el servidor. Nunca se inventa a
     * partir de "0.4.0": eso hacía que v0.4.0 pareciera 400 frente a code=6.
     */
    fun explicitCode(vararg sources: String?): Int? {
        val patterns = listOf(
            Regex(
                """(?i)(?:version_?code|versioncode|code|c[oó]digo|build)\s*[:=#\-]?\s*(\d{1,10})""",
            ),
            Regex("""(?i)\(\s*(?:v|code|build)?\s*(\d{1,10})\s*\)"""),
            Regex("""(?i)[#](\d{1,10})(?![\d.])"""),
            Regex("""(?i)[\s_.\-](?:code|build)[\s_.\-]*(\d{1,10})(?![\d.])"""),
            Regex("""(?i)^v(\d{1,10})$"""),
        )
        for (source in sources) {
            if (source.isNullOrBlank()) continue
            for (pattern in patterns) {
                val value = pattern.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (value != null && value in 1..Int.MAX_VALUE) return value
            }
        }
        return null
    }

    /** Extrae un hash SHA-256 de notas de release o de un archivo .sha256. */
    fun extractSha256(vararg sources: String?): String? {
        val pattern = Regex("""(?i)(?:sha-?256|sha256)\s*[:=]?\s*([a-f0-9]{64})""")
        for (source in sources) {
            if (source.isNullOrBlank()) continue
            val value = pattern.find(source)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return value.lowercase()
        }
        // Archivos tipo "abc123  app.apk" o una línea suelta de 64 hex.
        for (source in sources) {
            if (source.isNullOrBlank()) continue
            val line = source.lineSequence()
                .map { it.trim() }
                .firstOrNull { Regex("""(?i)^[a-f0-9]{64}\s+\S+""").matches(it) }
            if (!line.isNullOrBlank()) return line.take(64).lowercase()
        }
        val raw = sources.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        return if (Regex("""(?i)^[a-f0-9]{64}$""").matches(raw)) raw.lowercase() else null
    }
}
