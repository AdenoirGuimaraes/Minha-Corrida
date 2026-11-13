package br.com.minhacorrida.core.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Regras do backup do Minha Corrida (o que entra, nome do arquivo, quais arquivos dá para
 * restaurar). A gravação/leitura do JSON fica no app (Android); aqui só as decisões, testáveis.
 *
 * Entra: configurações, metas e deduções, histórico de corridas, km do GPS, abastecimentos,
 * trajetos, checks do deslocamento e onde o carro ficou estacionado.
 * Não entra: estado da leitura (pausada), trajeto em andamento e diagnóstico (são do momento).
 */
object BackupPolicy {
    const val FORMAT = "minha-corrida-backup"
    const val VERSION = 1

    /** Preferências (SharedPreferences) que vão no backup. */
    val PREFERENCES: List<String> = listOf(
        "minha_corrida_prefs",
        "minha_corrida_commute_days",
        "minha_corrida_km_log",
        "minha_corrida_fuel_log",
        "minha_corrida_trips",
        "minha_corrida_parking",
    )

    /** Arquivos da pasta do app que vão no backup. */
    val FILES: List<String> = listOf("historico_corridas.json")

    private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    /** "MinhaCorrida-backup-2026-11-13_0104.json". */
    fun fileName(now: LocalDateTime): String = "MinhaCorrida-backup-${now.format(NAME_TIME)}.json"

    /** Resultado da conferência de um arquivo escolhido para restaurar. */
    enum class Check { OK, NOT_A_BACKUP, NEWER_VERSION }

    /**
     * @param format campo "format" do arquivo (null se não tiver).
     * @param version campo "version" do arquivo.
     */
    fun check(format: String?, version: Int): Check = when {
        format != FORMAT -> Check.NOT_A_BACKUP
        version > VERSION -> Check.NEWER_VERSION
        else -> Check.OK
    }

    /** Só restaura o que está na lista (um arquivo adulterado não grava onde não deve). */
    fun canRestorePreference(name: String) = name in PREFERENCES
    fun canRestoreFile(name: String) = name in FILES
}

/**
 * O que um backup contém, para o motorista confirmar antes de restaurar.
 * Ex.: "Backup de 13/11/2026 01:04 (versão 0.2.50): 120 corridas, 5 abastecimentos, 40 trajetos."
 */
data class BackupSummary(
    val createdAt: LocalDateTime?,
    val appVersion: String?,
    val rides: Int,
    val fuelEntries: Int,
    val trips: Int,
) {
    fun describe(): String {
        val date = createdAt?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) ?: "data desconhecida"
        val version = appVersion?.let { " (versão $it)" }.orEmpty()
        return "Backup de $date$version: $rides corrida(s), $fuelEntries abastecimento(s), $trips trajeto(s)."
    }
}
