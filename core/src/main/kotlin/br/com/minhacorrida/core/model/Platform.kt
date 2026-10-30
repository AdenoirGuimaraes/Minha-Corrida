package br.com.minhacorrida.core.model

/**
 * Plataformas suportadas e os pacotes Android dos apps de motorista.
 * Para suportar um novo app basta adicionar uma entrada aqui e um parser (OCP).
 */
enum class Platform(val displayName: String, val packageNames: Set<String>) {
    UBER("Uber", setOf("com.ubercab.driver")),
    INDRIVE("inDrive", setOf("sinet.startup.inDriver"));

    companion object {
        /** Retorna a plataforma dona do pacote, ou null se não for suportado. */
        fun fromPackage(packageName: String): Platform? =
            entries.firstOrNull { packageName in it.packageNames }
    }
}
