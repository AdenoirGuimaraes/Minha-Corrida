package br.com.minhacorrida.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.BuildConfig
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.R
import br.com.minhacorrida.core.model.OverlayTimeout
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.update.AppRelease
import br.com.minhacorrida.di.AppContainer
import br.com.minhacorrida.service.RideReaderService
import br.com.minhacorrida.update.ApkUpdateInstaller
import java.io.File
import java.util.concurrent.Executors

/** Tela de configuração: status do serviço, mínimo por km, tempo do leitor e atualização. */
class MainActivity : Activity() {

    private lateinit var container: AppContainer
    private lateinit var installer: ApkUpdateInstaller
    private lateinit var tvStatus: TextView
    private lateinit var etMinValue: EditText
    private lateinit var etTimeout: EditText

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** APK baixado aguardando o usuário liberar a instalação. */
    private var pendingApk: File? = null

    /** Evita consultar o GitHub várias vezes seguidas e empilhar avisos. */
    private var lastUpdateCheckMs = 0L
    private var updateFlowActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = (application as MinhaCorridaApp).container
        installer = ApkUpdateInstaller(this, container.logger)

        tvStatus = findViewById(R.id.tvStatus)
        etMinValue = findViewById(R.id.etMinValue)
        etTimeout = findViewById(R.id.etTimeout)
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        val settings = container.settings
        etMinValue.setText(settings.getMinValuePerKm().toPlainString().replace('.', ','))
        etTimeout.setText(settings.getOverlayTimeoutSeconds().toString())

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener { showDiagnostics() }
    }

    override fun onResume() {
        super.onResume()
        tvStatus.setText(if (isServiceEnabled()) R.string.status_on else R.string.status_off)

        // Voltou da tela de permissão: instala o APK que já estava baixado.
        val apk = pendingApk
        if (apk != null && installer.canInstall()) {
            pendingApk = null
            installer.install(this, apk)
            return
        }
        // Toda vez que o app é aberto (ou volta para a frente), procura versão nova.
        checkForUpdate()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        installer.shutdown()
        super.onDestroy()
    }

    // ---- Atualização ----

    /**
     * Consulta o GitHub fora da thread principal e mostra o aviso se houver versão nova.
     * Ignorada enquanto um aviso/download está aberto ou se a última consulta foi há pouco.
     */
    private fun checkForUpdate() {
        val now = SystemClock.elapsedRealtime()
        if (updateFlowActive || (lastUpdateCheckMs != 0L && now - lastUpdateCheckMs < UPDATE_CHECK_INTERVAL_MS)) return
        lastUpdateCheckMs = now
        try {
            ioExecutor.execute {
                val release = container.checkForUpdate()
                mainHandler.post {
                    if (release != null && !isFinishing && !isDestroyed && !updateFlowActive) showUpdateDialog(release)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao agendar verificação de atualização", e)
        }
    }

    private fun showUpdateDialog(release: AppRelease) {
        updateFlowActive = true
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, release.versionName, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(release) }
            .setNegativeButton(R.string.update_later) { _, _ -> updateFlowActive = false }
            .setOnCancelListener { updateFlowActive = false }
            .show()
    }

    private fun startDownload(release: AppRelease) {
        val progressText = TextView(this).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            textSize = 16f
            text = getString(R.string.update_downloading, 0)
        }
        val dialog = AlertDialog.Builder(this).setView(progressText).setCancelable(false).show()

        installer.download(
            release,
            onProgress = { percent -> progressText.text = getString(R.string.update_downloading, percent) },
            onFinished = { apk ->
                updateFlowActive = false
                if (!isDestroyed) dialog.dismiss()
                if (apk == null) toast(getString(R.string.update_failed)) else installOrAskPermission(apk)
            },
        )
    }

    private fun installOrAskPermission(apk: File) {
        if (installer.canInstall()) {
            installer.install(this, apk)
        } else {
            pendingApk = apk
            toast(getString(R.string.update_allow_install))
            installer.openInstallPermissionSettings(this)
        }
    }

    // ---- Diagnóstico ----

    /** Mostra a última leitura de cada app, com opção de compartilhar o texto. */
    private fun showDiagnostics() {
        val report = container.diagnostics.dump()
        val content = TextView(this).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            textSize = 12f
            text = report
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.diagnostics_share) { _, _ -> shareText(report) }
            .setNeutralButton(R.string.diagnostics_clear) { _, _ -> container.diagnostics.clear() }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun shareText(text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao compartilhar diagnóstico", e)
        }
    }

    // ---- Configurações ----

    /** Valida os dois campos antes de gravar qualquer um deles. */
    private fun saveSettings() {
        try {
            val minValue = NumberParser.parse(etMinValue.text.toString())
            if (minValue == null || minValue.signum() <= 0) {
                toast(getString(R.string.invalid_value))
                return
            }
            val timeout = etTimeout.text.toString().trim().toIntOrNull()
            if (timeout == null || !OverlayTimeout.isValid(timeout)) {
                toast(getString(R.string.invalid_timeout, OverlayTimeout.MIN_SECONDS, OverlayTimeout.MAX_SECONDS))
                return
            }
            container.settings.setMinValuePerKm(minValue)
            container.settings.setOverlayTimeoutSeconds(timeout)
            toast(getString(R.string.saved))
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao salvar configurações", e)
            toast(getString(R.string.save_error))
        }
    }

    private companion object {
        /** Intervalo mínimo entre consultas ao GitHub (abrir e fechar o app rápido não repete). */
        const val UPDATE_CHECK_INTERVAL_MS = 60_000L
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(this, RideReaderService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
