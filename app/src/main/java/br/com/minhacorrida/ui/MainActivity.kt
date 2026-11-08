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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.BuildConfig
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.R
import br.com.minhacorrida.core.model.OperatingCost
import br.com.minhacorrida.core.model.OverlayTimeout
import br.com.minhacorrida.core.model.Platform
import br.com.minhacorrida.core.model.RideAnalysis
import br.com.minhacorrida.core.model.RideOffer
import br.com.minhacorrida.core.model.RideRating
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.core.update.AppRelease
import br.com.minhacorrida.core.usecase.RideRules
import br.com.minhacorrida.di.AppContainer
import br.com.minhacorrida.service.RideReaderService
import br.com.minhacorrida.service.VoiceAnnouncer
import br.com.minhacorrida.update.ApkUpdateInstaller
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * Tela de configurações (tema preto e amarelo):
 * simulador do card no topo, status, regras, custos do carro, checklist de apps e voz,
 * salvar, diagnóstico, encerrar e atualização automática.
 */
class MainActivity : Activity() {

    private lateinit var container: AppContainer
    private lateinit var installer: ApkUpdateInstaller
    private lateinit var cardBinder: RideCardBinder
    private var voice: VoiceAnnouncer? = null

    private lateinit var tvStatus: TextView
    private lateinit var tabSettings: TextView
    private lateinit var tabSimulator: TextView
    private lateinit var tabHistory: TextView
    private lateinit var pageHistory: View
    private lateinit var historyScreen: RideHistoryScreen

    private enum class Tab { SETTINGS, SIMULATOR, HISTORY }
    private var currentTab = Tab.HISTORY
    private lateinit var pageSettings: View
    private lateinit var pageSimulator: View
    private lateinit var previewCard: View
    private lateinit var tvPreviewMessage: TextView

    // Corrida de exemplo (só para o simulador, não é salva).
    private lateinit var rgSimApp: RadioGroup
    private lateinit var rbSimInDrive: RadioButton
    private lateinit var etSimFare: EditText
    private lateinit var etSimRating: EditText
    private lateinit var etSimPickupKm: EditText
    private lateinit var etSimTripKm: EditText

    // Regras e custos.
    private lateinit var etMinValue: EditText
    private lateinit var etMinRating: EditText
    private lateinit var etInDriveFee: EditText
    private lateinit var etMinProfit: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etFuelPrice: EditText
    private lateinit var etKmPerLiter: EditText
    private lateinit var etOtherCost: EditText
    private lateinit var tvCostSummary: TextView

    // Checklist.
    private lateinit var cbUber: CheckBox
    private lateinit var cbInDrive: CheckBox
    private lateinit var cbVoice: CheckBox

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Última simulação calculada (usada pelo "Testar voz"). */
    private var previewAnalysis: RideAnalysis? = null

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
        cardBinder = RideCardBinder(container.formatter)
        voice = runCatching { VoiceAnnouncer(this, container.logger) }.getOrNull()

        bindViews()
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        loadSettings()
        fillSampleRide()

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.btnExit).setOnClickListener { exitApp() }
        findViewById<Button>(R.id.btnTestVoice).setOnClickListener { testVoice() }
        tabSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
        tabSimulator.setOnClickListener { selectTab(Tab.SIMULATOR) }
        tabHistory.setOnClickListener { selectTab(Tab.HISTORY) }
        historyScreen = RideHistoryScreen(
            this, container.rideHistory, container.historyFormatter, ioExecutor,
            container.evaluateRide, container.settings, container.formatter,
        )
        historyScreen.bind()
        rgSimApp.setOnCheckedChangeListener { _, _ -> updatePreview() }

        watchFieldsForPreview()
        updatePreview()
        selectTab(Tab.HISTORY)
    }

    override fun onResume() {
        super.onResume()
        // Abrir o app = voltar a trabalhar: retoma a leitura se tinha sido encerrada.
        container.readingSession.start()
        // Voltando ao app na aba Corridas: mostra as corridas verdes que chegaram nesse meio-tempo.
        if (::historyScreen.isInitialized && currentTab == Tab.HISTORY) historyScreen.refresh()
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
        voice?.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tabSettings = findViewById(R.id.tabSettings)
        tabSimulator = findViewById(R.id.tabSimulator)
        pageSettings = findViewById(R.id.pageSettings)
        pageSimulator = findViewById(R.id.pageSimulator)
        tabHistory = findViewById(R.id.tabHistory)
        pageHistory = findViewById(R.id.pageHistory)
        previewCard = findViewById(R.id.previewCard)
        rgSimApp = findViewById(R.id.rgSimApp)
        rbSimInDrive = findViewById(R.id.rbSimInDrive)
        tvPreviewMessage = findViewById(R.id.tvPreviewMessage)
        etSimFare = findViewById(R.id.etSimFare)
        etSimRating = findViewById(R.id.etSimRating)
        etSimPickupKm = findViewById(R.id.etSimPickupKm)
        etSimTripKm = findViewById(R.id.etSimTripKm)
        etMinValue = findViewById(R.id.etMinValue)
        etMinRating = findViewById(R.id.etMinRating)
        etInDriveFee = findViewById(R.id.etInDriveFee)
        etMinProfit = findViewById(R.id.etMinProfit)
        etTimeout = findViewById(R.id.etTimeout)
        etFuelPrice = findViewById(R.id.etFuelPrice)
        etKmPerLiter = findViewById(R.id.etKmPerLiter)
        etOtherCost = findViewById(R.id.etOtherCost)
        tvCostSummary = findViewById(R.id.tvCostSummary)
        cbUber = findViewById(R.id.cbUber)
        cbInDrive = findViewById(R.id.cbInDrive)
        cbVoice = findViewById(R.id.cbVoice)
    }

    // ---- Abas ----

    /**
     * Mostra uma aba e esconde as outras. Os campos continuam vivos, então o simulador
     * usa o que foi digitado em Configurações mesmo antes de salvar.
     */
    private fun selectTab(tab: Tab) {
        currentTab = tab
        pageSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        pageSimulator.visibility = if (tab == Tab.SIMULATOR) View.VISIBLE else View.GONE
        pageHistory.visibility = if (tab == Tab.HISTORY) View.VISIBLE else View.GONE
        styleTab(tabSettings, selected = tab == Tab.SETTINGS)
        styleTab(tabSimulator, selected = tab == Tab.SIMULATOR)
        styleTab(tabHistory, selected = tab == Tab.HISTORY)
        when (tab) {
            Tab.SIMULATOR -> updatePreview()
            Tab.HISTORY -> historyScreen.refresh()
            Tab.SETTINGS -> Unit
        }
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
        tab.setTextColor(getColor(if (selected) R.color.mc_black else R.color.mc_yellow))
    }

    // ---- Simulador ----

    /** Corrida real do log de 02/11 como ponto de partida (o motorista pode mudar à vontade). */
    private fun fillSampleRide() {
        etSimFare.setText("7,04")
        etSimRating.setText("4,82")
        etSimPickupKm.setText("4,0")
        etSimTripKm.setText("3,0")
    }

    /** Qualquer número digitado (regras, custos ou corrida de exemplo) recalcula o card. */
    private fun watchFieldsForPreview() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(
            etSimFare, etSimRating, etSimPickupKm, etSimTripKm,
            etMinValue, etMinRating, etMinProfit, etInDriveFee, etFuelPrice, etKmPerLiter, etOtherCost,
        ).forEach { it.addTextChangedListener(watcher) }
    }

    /**
     * Recalcula o card com os valores DIGITADOS (mesmo antes de salvar), usando a mesma
     * regra do leitor de tela. Campos opcionais inválidos são ignorados na simulação.
     */
    private fun updatePreview() {
        try {
            val minValue = positive(etMinValue)
            val fare = positive(etSimFare)
            val tripKm = positive(etSimTripKm)
            val pickupKm = nonNegativeOrNull(etSimPickupKm)
            val cost = costFromFields()
            updateCostSummary(cost, minValue)

            if (minValue == null || fare == null || tripKm == null) {
                showPreviewMessage(getString(R.string.preview_invalid))
                return
            }
            val platform = if (rbSimInDrive.isChecked) Platform.INDRIVE else Platform.UBER
            val offer = RideOffer(
                platform, fare, tripKm = tripKm, pickupKm = pickupKm,
                passengerRating = ratingOrNull(etSimRating),
            )
            val rules = RideRules(
                minValuePerKm = minValue,
                minPassengerRating = ratingOrNull(etMinRating),
                operatingCost = cost,
                inDriveFeePercent = feeOrNull(etInDriveFee) ?: BigDecimal.ZERO,
                minNetProfit = nonNegativeOrNull(etMinProfit),
            )
            val analysis = container.evaluateRide(offer, rules)
            previewAnalysis = analysis
            cardBinder.bind(previewCard, analysis)
            previewCard.visibility = View.VISIBLE
            tvPreviewMessage.visibility = View.GONE
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao simular o card", e)
            showPreviewMessage(getString(R.string.preview_invalid))
        }
    }

    private fun showPreviewMessage(message: String) {
        previewAnalysis = null
        previewCard.visibility = View.INVISIBLE
        tvPreviewMessage.text = message
        tvPreviewMessage.visibility = View.VISIBLE
    }

    /** Fala a frase da corrida simulada, com as mesmas condições do leitor (verde + voz marcada). */
    private fun testVoice() {
        val analysis = previewAnalysis ?: return
        when {
            !cbVoice.isChecked -> toast(getString(R.string.test_voice_off))
            analysis.rating != RideRating.GOOD -> toast(getString(R.string.test_voice_red))
            else -> voice?.speak(container.formatter.speech(analysis))
        }
    }

    private fun updateCostSummary(cost: OperatingCost?, minValue: BigDecimal?) {
        tvCostSummary.text = if (cost == null || minValue == null) {
            getString(R.string.cost_not_set)
        } else {
            container.formatter.costSummary(cost, minValue)
        }
    }

    /** Custos digitados, se preço e consumo forem válidos; senão null (sem lucro na simulação). */
    private fun costFromFields(): OperatingCost? {
        val fuel = positive(etFuelPrice) ?: return null
        val kmPerLiter = positive(etKmPerLiter) ?: return null
        val other = nonNegativeOrNull(etOtherCost) ?: BigDecimal.ZERO
        return OperatingCost(fuel, kmPerLiter, other)
    }

    private fun number(field: EditText): BigDecimal? = NumberParser.parse(field.text.toString())

    private fun positive(field: EditText): BigDecimal? = number(field)?.takeIf { it.signum() > 0 }

    private fun nonNegativeOrNull(field: EditText): BigDecimal? = number(field)?.takeIf { it.signum() >= 0 }

    /** Taxa entre 0 e 50%; vazio/inválido = null. */
    private fun feeOrNull(field: EditText): BigDecimal? =
        number(field)?.takeIf { it.signum() >= 0 && it <= BigDecimal(50) }

    private fun ratingOrNull(field: EditText): BigDecimal? =
        number(field)?.takeIf { it >= BigDecimal.ONE && it <= BigDecimal(5) }

    // ---- Configurações ----

    private fun loadSettings() {
        val settings = container.settings
        etMinValue.setText(decimalText(settings.getMinValuePerKm()))
        etTimeout.setText(settings.getOverlayTimeoutSeconds().toString())
        settings.getOperatingCost()?.let { cost ->
            etFuelPrice.setText(decimalText(cost.fuelPricePerLiter))
            etKmPerLiter.setText(decimalText(cost.kmPerLiter))
            if (cost.otherCostPerKm.signum() > 0) etOtherCost.setText(decimalText(cost.otherCostPerKm))
        }
        settings.getMinPassengerRating()?.let { etMinRating.setText(decimalText(it)) }
        etInDriveFee.setText(decimalText(settings.getInDriveFeePercent()))
        settings.getMinNetProfit()?.let { etMinProfit.setText(decimalText(it)) }
        val platforms = settings.getEnabledPlatforms()
        cbUber.isChecked = Platform.UBER in platforms
        cbInDrive.isChecked = Platform.INDRIVE in platforms
        cbVoice.isChecked = settings.isVoiceEnabled()
    }

    /** Valida todos os campos antes de gravar qualquer um deles. */
    private fun saveSettings() {
        try {
            val minValue = positive(etMinValue)
            if (minValue == null) {
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
            val inDriveFee = if (etInDriveFee.text.isBlank()) BigDecimal.ZERO else feeOrNull(etInDriveFee)
            if (inDriveFee == null) {
                toast(getString(R.string.invalid_fee))
                return
            }
            val minProfit = if (etMinProfit.text.isBlank()) null else nonNegativeOrNull(etMinProfit)
            if (etMinProfit.text.isNotBlank() && minProfit == null) {
                toast(getString(R.string.invalid_min_profit))
                return
            }
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
            settings.setInDriveFeePercent(inDriveFee)
            settings.setMinNetProfit(minProfit)
            settings.setEnabledPlatforms(platforms)
            settings.setVoiceEnabled(cbVoice.isChecked)
            toast(getString(R.string.saved))
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao salvar configurações", e)
            toast(getString(R.string.save_error))
        }
    }

    /** Campo opcional já validado: [value] null significa "não informado". */
    private class Optional<T>(val value: T?)

    /**
     * Preço e consumo: os dois vazios = sem cálculo de lucro; só um preenchido = erro.
     * @return null quando inválido (o motivo já foi mostrado).
     */
    private fun readOperatingCost(): Optional<OperatingCost>? {
        val fuelText = etFuelPrice.text.toString().trim()
        val kmText = etKmPerLiter.text.toString().trim()
        if (fuelText.isEmpty() && kmText.isEmpty()) return Optional(null)

        val fuel = positive(etFuelPrice)
        val kmPerLiter = positive(etKmPerLiter)
        val otherText = etOtherCost.text.toString().trim()
        val other = if (otherText.isEmpty()) BigDecimal.ZERO else nonNegativeOrNull(etOtherCost)
        if (fuel == null || kmPerLiter == null || other == null) {
            toast(getString(R.string.invalid_cost))
            return null
        }
        return Optional(OperatingCost(fuel, kmPerLiter, other))
    }

    /** Vazio = sem filtro de nota. @return null quando inválido (o motivo já foi mostrado). */
    private fun readMinRating(): Optional<BigDecimal>? {
        if (etMinRating.text.toString().isBlank()) return Optional(null)
        val rating = ratingOrNull(etMinRating)
        if (rating == null) {
            toast(getString(R.string.invalid_rating))
            return null
        }
        return Optional(rating)
    }

    private fun decimalText(value: BigDecimal) = value.stripTrailingZeros().toPlainString().replace('.', ',')

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

    /**
     * Resumo do diagnóstico (quantas leituras e as 10 últimas) com o botão que exporta o
     * relatório completo em Markdown (.md) para anexar no chat de calibração.
     */
    private fun showDiagnostics() {
        val entries = try {
            container.diagnostics.entries()
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao ler diagnóstico", e)
            emptyList()
        }
        val preview = if (entries.isEmpty()) {
            getString(R.string.diagnostics_empty)
        } else {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("pt", "BR"))
            getString(R.string.diagnostics_summary, entries.size, br.com.minhacorrida.infra.DiagnosticsStore.MAX_ENTRIES) + "\n\n" +
                entries.takeLast(PREVIEW_ENTRIES).asReversed().joinToString("\n") { e ->
                    val app = Platform.fromPackage(e.packageName)?.displayName ?: e.packageName
                    "• ${time.format(java.util.Date(e.timestampMillis))} · $app · ${e.result.take(70)}"
                }
        }
        val content = TextView(this).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            textSize = 13f
            text = preview
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.diagnostics_export) { _, _ -> exportDiagnostics(entries) }
            .setNeutralButton(R.string.diagnostics_clear) { _, _ ->
                container.diagnostics.clear()
                toast(getString(R.string.diagnostics_cleared))
            }
            .setNegativeButton(R.string.diagnostics_close, null)
            .show()
    }

    /** Gera o relatório .md (versão, aparelho, regras e leituras) e abre o compartilhar do Android. */
    private fun exportDiagnostics(entries: List<br.com.minhacorrida.core.diagnostics.DiagnosticEntry>) {
        try {
            if (entries.isEmpty()) {
                toast(getString(R.string.diagnostics_empty))
                return
            }
            val now = System.currentTimeMillis()
            val metrics = resources.displayMetrics
            val header = br.com.minhacorrida.core.diagnostics.DiagnosticsHeader(
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, " +
                    "tela ${metrics.widthPixels}×${metrics.heightPixels}",
                rules = rulesSummary(),
                generatedAtMillis = now,
            )
            val markdown = container.diagnosticsReport.markdown(header, entries)
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // só o relatório mais recente fica
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now))
            val file = File(dir, "minha-corrida-diagnostico-$stamp.md").apply { writeText(markdown) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri(file.name, uri)
            startActivity(Intent.createChooser(send, getString(R.string.diagnostics_export)))
        } catch (e: Exception) {
            android.util.Log.e("MC/MainActivity", "Falha ao exportar diagnóstico", e)
            toast(getString(R.string.diagnostics_export_failed))
        }
    }

    /** Regras de agora, para o cabeçalho do relatório. */
    private fun rulesSummary(): String = try {
        val settings = container.settings
        val parts = mutableListOf("mínimo R$ ${decimalText(settings.getMinValuePerKm())}/km")
        settings.getMinNetProfit()?.let { parts += "lucro mín. R$ ${decimalText(it)}" }
        parts += "taxa inDrive ${decimalText(settings.getInDriveFeePercent())}%"
        settings.getMinPassengerRating()?.let { parts += "nota mín. ${decimalText(it)}" }
        parts += "custos " + if (settings.getOperatingCost() != null) "informados" else "não informados"
        parts += "apps: " + settings.getEnabledPlatforms().joinToString { it.displayName }
        parts.joinToString(" · ")
    } catch (e: Exception) {
        "indisponível"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(this, RideReaderService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private companion object {
        const val PREVIEW_ENTRIES = 10
        /** Intervalo mínimo entre consultas ao GitHub (abrir e fechar o app rápido não repete). */
        const val UPDATE_CHECK_INTERVAL_MS = 60_000L
    }
}
