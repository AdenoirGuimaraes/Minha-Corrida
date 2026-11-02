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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.BuildConfig
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.R
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.OverlayTimeout
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.update.AppRelease
import br.com.minhacorrida.di.AppContainer
import br.com.minhacorrida.service.RideReaderService
import br.com.minhacorrida.update.ApkUpdateInstaller
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.Executors

/** Tela de configuração: status do serviço, mínimo por km, tempo do leitor e atualização. */
class MainActivity : Activity() {

    private lateinit var container: AppContainer
    private lateinit var installer: ApkUpdateInstaller
    private lateinit var tvStatus: TextView
    private lateinit var etMinValue: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etFuelPrice: EditText
    private lateinit var etKmPerLiter: EditText
    private lateinit var etOtherCost: EditText
    private lateinit var tvCostSummary: TextView
    private lateinit var etMinRating: EditText
    private lateinit var cbUber: CheckBox
    private lateinit var cbInDrive: CheckBox
    private lateinit var swVoice: Switch

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
        etFuelPrice = findViewById(R.id.etFuelPrice)
        etKmPerLiter = findViewById(R.id.etKmPerLiter)
        etOtherCost = findViewById(R.id.etOtherCost)
        tvCostSummary = findViewById(R.id.tvCostSummary)
        etMinRating = findViewById(R.id.etMinRating)
        cbUber = findViewById(R.id.cbUber)
        cbInDrive = findViewById(R.id.cbInDrive)
        swVoice = findViewById(R.id.swVoice)
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        val settings = container.settings
        etMinValue.setText(settings.getMinValuePerKm().toPlainString().replace('.', ','))
        etTimeout.setText(settings.getOverlayTimeoutSeconds().toString())
        loadProfitSettings()

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.btnExit).setOnClickListener { exitApp() }
    }

    override fun onResume() {
        super.onResume()
        // Abrir o app = voltar a trabalhar: retoma a leitura se tinha sido encerrada.
        container.readingSession.start()
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

    // ---- Encerrar ----

    /**
     * Pausa a leitura (sem OCR nem leitor na tela) e fecha o app, tirando-o dos recentes.
     * A acessibilidade continua ativada para não precisar religá-la nas Configurações;
     * abrir o app de novo retoma a leitura.
     */
    private fun exitApp() {
        try {
            container.readingSession.stop()
            toast(getString(R.string.exit_done))
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao encerrar a leitura", e)
        } finally {
            finishAndRemoveTask()
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
            val cost = readOperatingCost() ?: return
            val minRating = readMinRating() ?: return
            val platforms = buildSet {
                if (cbUber.isChecked) add(Platform.UBER)
                if (cbInDrive.isChecked) add(Platform.INDRIVE)
            }
            if (platforms.isEmpty()) {
                toast(getString(R.string.invalid_platforms))
                return
            }

            // Tudo validado: grava de uma vez.
            val settings = container.settings
            settings.setMinValuePerKm(minValue)
            settings.setOverlayTimeoutSeconds(timeout)
            settings.setOperatingCost(cost.value)
            settings.setMinPassengerRating(minRating.value)
            settings.setEnabledPlatforms(platforms)
            settings.setVoiceEnabled(swVoice.isChecked)
            showCostSummary()
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

    // ---- Lucro, nota, apps e voz ----

    /** Campo opcional já validado: [value] null significa "não informado". */
    private class Optional<T>(val value: T?)

    private fun loadProfitSettings() {
        val settings = container.settings
        settings.getOperatingCost()?.let { cost ->
            etFuelPrice.setText(decimalText(cost.fuelPricePerLiter))
            etKmPerLiter.setText(decimalText(cost.kmPerLiter))
            if (cost.otherCostPerKm.signum() > 0) etOtherCost.setText(decimalText(cost.otherCostPerKm))
        }
        settings.getMinPassengerRating()?.let { etMinRating.setText(decimalText(it)) }
        val platforms = settings.getEnabledPlatforms()
        cbUber.isChecked = Platform.UBER in platforms
        cbInDrive.isChecked = Platform.INDRIVE in platforms
        swVoice.isChecked = settings.isVoiceEnabled()
        showCostSummary()
    }

    /**
     * Preço e consumo: os dois vazios = sem cálculo de lucro; só um preenchido = erro.
     * @return null quando inválido (o motivo já foi mostrado).
     */
    private fun readOperatingCost(): Optional<OperatingCost>? {
        val fuelText = etFuelPrice.text.toString().trim()
        val kmText = etKmPerLiter.text.toString().trim()
        if (fuelText.isEmpty() && kmText.isEmpty()) return Optional(null)

        val fuel = NumberParser.parse(fuelText)
        val kmPerLiter = NumberParser.parse(kmText)
        val otherText = etOtherCost.text.toString().trim()
        val other = if (otherText.isEmpty()) BigDecimal.ZERO else NumberParser.parse(otherText)
        if (fuel == null || fuel.signum() <= 0 || kmPerLiter == null || kmPerLiter.signum() <= 0 ||
            other == null || other.signum() < 0
        ) {
            toast(getString(R.string.invalid_cost))
            return null
        }
        return Optional(OperatingCost(fuel, kmPerLiter, other))
    }

    /** Vazio = sem filtro de nota. @return null quando inválido (o motivo já foi mostrado). */
    private fun readMinRating(): Optional<BigDecimal>? {
        val text = etMinRating.text.toString().trim()
        if (text.isEmpty()) return Optional(null)
        val rating = NumberParser.parse(text)
        if (rating == null || rating < BigDecimal.ONE || rating > BigDecimal(5)) {
            toast(getString(R.string.invalid_rating))
            return null
        }
        return Optional(rating)
    }

    private fun showCostSummary() {
        val cost = container.settings.getOperatingCost()
        tvCostSummary.text = if (cost == null) {
            getString(R.string.cost_not_set)
        } else {
            container.formatter.costSummary(cost, container.settings.getMinValuePerKm())
        }
    }

    private fun decimalText(value: BigDecimal) = value.stripTrailingZeros().toPlainString().replace('.', ',')

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(this, RideReaderService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
