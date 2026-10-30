package br.com.minhacorrida.core.update

/** Versão publicada do app, disponível para download. */
data class AppRelease(val versionCode: Int, val apkUrl: String) {
    /** Nome exibido ao motorista; segue a mesma regra do app/build.gradle.kts ("0.2.<versionCode>"). */
    val versionName: String get() = AppVersion.nameOf(versionCode)
}

/** Regra única do nome da versão a partir do número da compilação. */
object AppVersion {
    fun nameOf(versionCode: Int): String = "0.2.$versionCode"
}

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
