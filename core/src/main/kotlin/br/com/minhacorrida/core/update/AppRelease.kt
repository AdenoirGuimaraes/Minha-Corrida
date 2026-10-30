package br.com.minhacorrida.core.update

/** Versão publicada do app, disponível para download. */
data class AppRelease(val versionCode: Int, val apkUrl: String)

/** Fonte das versões publicadas (implementação Android consulta o GitHub Releases). */
interface ReleaseSource {
    /** @return a última versão publicada, ou null se indisponível. */
    fun latest(): AppRelease?
}

/** Extrai o número da versão das notas da release ("versionCode=12"). */
object ReleaseNotesParser {
    private val VERSION_CODE = Regex("""versionCode\s*=\s*(\d+)""")

    fun versionCode(notes: String?): Int? =
        notes?.let { VERSION_CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
