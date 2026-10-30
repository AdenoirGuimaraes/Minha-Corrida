package br.com.minhacorrida.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.R
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.port.SettingsRepository
import br.com.minhacorrida.service.RideReaderService

/** Tela de configuração: status do serviço e valor mínimo por km. */
class MainActivity : Activity() {

    private lateinit var settings: SettingsRepository
    private lateinit var tvStatus: TextView
    private lateinit var etMinValue: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val container = (application as MinhaCorridaApp).container
        settings = container.settings

        tvStatus = findViewById(R.id.tvStatus)
        etMinValue = findViewById(R.id.etMinValue)
        etMinValue.setText(settings.getMinValuePerKm().toPlainString().replace('.', ','))

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveMinValue() }
    }

    override fun onResume() {
        super.onResume()
        tvStatus.setText(if (isServiceEnabled()) R.string.status_on else R.string.status_off)
    }

    private fun saveMinValue() {
        try {
            val value = NumberParser.parse(etMinValue.text.toString())
            if (value == null || value.signum() <= 0) {
                Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                return
            }
            settings.setMinValuePerKm(value)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao salvar mínimo", e)
            Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(this, RideReaderService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
