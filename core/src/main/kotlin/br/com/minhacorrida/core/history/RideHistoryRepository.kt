package br.com.minhacorrida.core.history

/** Onde o histórico de corridas fica guardado (implementação Android em arquivo JSON). */
interface RideHistoryRepository {
    fun add(record: RideRecord)

    /** Corridas com [RideRecord.timestampMillis] em [fromMillis, toMillis), da mais recente para a mais antiga. */
    fun between(fromMillis: Long, toMillis: Long): List<RideRecord>

    fun setDone(id: String, done: Boolean)

    /** Apaga as corridas anteriores a [cutoffMillis]. @return quantas foram apagadas. */
    fun deleteOlderThan(cutoffMillis: Long): Int
}

/** Implementação em memória (testes). */
class InMemoryRideHistoryRepository : RideHistoryRepository {
    private val records = mutableListOf<RideRecord>()

    override fun add(record: RideRecord) {
        records += record
    }

    override fun between(fromMillis: Long, toMillis: Long): List<RideRecord> =
        records.filter { it.timestampMillis in fromMillis until toMillis }.sortedByDescending { it.timestampMillis }

    override fun setDone(id: String, done: Boolean) {
        val i = records.indexOfFirst { it.id == id }
        if (i >= 0) records[i] = records[i].copy(done = done)
    }

    override fun deleteOlderThan(cutoffMillis: Long): Int {
        val before = records.size
        records.removeAll { it.timestampMillis < cutoffMillis }
        return before - records.size
    }
}
