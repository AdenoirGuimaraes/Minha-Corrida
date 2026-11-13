package br.com.minhacorrida.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.location.LocationManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.R
import br.com.minhacorrida.core.destination.DestinationSettingsRepository
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.parser.NumberParser

/**
 * Seção "Destino da corrida" das Configurações: liga/desliga a linha do destino no card,
 * marca a base (localização atual), a distância de "longe" e os bairros a evitar.
 * Liga/desliga e base gravam na hora; "longe" e bairros gravam no botão Salvar ([save]).
 */
class DestinationSection(
    private val activity: Activity,
    private val settings: DestinationSettingsRepository,
) {
    private val cbEnabled: CheckBox = activity.findViewById(R.id.cbDestination)
    private val tvBase: TextView = activity.findViewById(R.id.tvDestBase)
    private val etFar: EditText = activity.findViewById(R.id.etDestFar)
    private val etAvoid: EditText = activity.findViewById(R.id.etDestAvoid)

    fun bind() {
        DecimalMask.attach(etFar, 1)
        val current = settings.get()
        cbEnabled.isChecked = current.enabled
        DecimalMask.set(etFar, current.farKm)
        etAvoid.setText(current.avoid.joinToString(", "))
        showBase()
        cbEnabled.setOnCheckedChangeListener { _, checked -> settings.save(settings.get().copy(enabled = checked)) }
        activity.findViewById<Button>(R.id.btnDestBase).setOnClickListener { useCurrentLocation() }
    }

    /**
     * Grava "longe" e bairros (chamado pelo Salvar das Configurações).
     * @return false se o km de "longe" for inválido.
     */
    fun save(): Boolean {
        val far = NumberParser.parse(etFar.text.toString())?.takeIf { it.signum() > 0 } ?: return false
        val avoid = etAvoid.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        settings.save(settings.get().copy(farKm = far.setScale(1, java.math.RoundingMode.HALF_UP), avoid = avoid))
        return true
    }

    private fun showBase() {
        val s = settings.get()
        tvBase.text = if (s.baseIsDefault) {
            activity.getString(R.string.dest_base_default)
        } else {
            activity.getString(R.string.dest_base_set, coordinate(s.base.latitude), coordinate(s.base.longitude))
        }
    }

    /** Última posição conhecida do GPS (ou da rede) vira a base. Precisa da permissão de localização. */
    @SuppressLint("MissingPermission")
    private fun useCurrentLocation() {
        try {
            if (!br.com.minhacorrida.service.KmTrackerService.hasLocationPermission(activity)) {
                activity.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
                return
            }
            val manager = activity.getSystemService(LocationManager::class.java)
            val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (location == null) {
                toast(activity.getString(R.string.dest_base_failed))
                return
            }
            settings.save(
                settings.get().copy(base = GeoPoint(location.latitude, location.longitude, 0f, 0), baseIsDefault = false),
            )
            showBase()
            toast(activity.getString(R.string.dest_base_saved))
        } catch (e: Exception) {
            android.util.Log.e("MC/Destination", "Falha ao marcar a base", e)
            toast(activity.getString(R.string.dest_base_failed))
        }
    }

    private fun coordinate(value: Double) = String.format(java.util.Locale("pt", "BR"), "%.4f", value)
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_LOCATION = 41
    }
}
