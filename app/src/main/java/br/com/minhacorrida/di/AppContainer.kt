package br.com.minhacorrida.di

import android.content.Context
import br.com.minhacorrida.BuildConfig
import br.com.minhacorrida.core.format.RideAnalysisFormatter
import br.com.minhacorrida.core.parser.InDriveOfferParser
import br.com.minhacorrida.core.parser.ParserRegistry
import br.com.minhacorrida.core.parser.UberOfferParser
import br.com.minhacorrida.core.port.AppLogger
import br.com.minhacorrida.core.port.ReadingSession
import br.com.minhacorrida.core.port.SettingsRepository
import br.com.minhacorrida.core.update.CheckForUpdateUseCase
import br.com.minhacorrida.core.usecase.AnalyzeScreenUseCase
import br.com.minhacorrida.core.usecase.CalculateRideMetricsUseCase
import br.com.minhacorrida.core.usecase.EvaluateRideUseCase
import br.com.minhacorrida.core.format.RideHistoryFormatter
import br.com.minhacorrida.core.history.RecordRideUseCase
import br.com.minhacorrida.core.history.RideHistoryRepository
import br.com.minhacorrida.core.history.RideHistoryUseCase
import br.com.minhacorrida.infra.AndroidLogger
import br.com.minhacorrida.infra.JsonFileRideHistoryRepository
import br.com.minhacorrida.infra.DiagnosticsStore
import br.com.minhacorrida.infra.SharedPrefsReadingSession
import br.com.minhacorrida.infra.SharedPrefsSettingsRepository
import br.com.minhacorrida.update.GitHubReleaseSource

/**
 * Injeção de dependências manual (sem framework). Novos apps de corrida
 * entram adicionando o parser na lista do [ParserRegistry].
 */
class AppContainer(context: Context) {
    val logger: AppLogger = AndroidLogger()
    val diagnostics = DiagnosticsStore(context, logger)
    private val prefsSettings = SharedPrefsSettingsRepository(context.applicationContext)
    val settings: SettingsRepository = prefsSettings
    /** Meta diária e deduções do lucro (mesmo arquivo de preferências, porta própria). */
    val goalSettings: br.com.minhacorrida.core.goal.GoalSettingsRepository = prefsSettings
    val goalFormatter = br.com.minhacorrida.core.format.GoalFormatter()
    /** Controle de km rodados (GPS) e abastecimentos (tanque cheio). */
    val kmSettings: br.com.minhacorrida.core.km.KmSettingsRepository = prefsSettings
    val kmLog: br.com.minhacorrida.core.km.KmLogRepository by lazy {
        br.com.minhacorrida.infra.SharedPrefsKmLogRepository(context, logger)
    }
    val fuelLog: br.com.minhacorrida.core.km.FuelLogRepository by lazy {
        br.com.minhacorrida.infra.SharedPrefsFuelLogRepository(context, logger)
    }
    /** Check de cada dia: descontar ou não a gasolina do deslocamento. */
    val commuteChoices: br.com.minhacorrida.core.km.CommuteChoiceRepository by lazy {
        br.com.minhacorrida.infra.SharedPrefsCommuteChoiceRepository(context)
    }
    /** Deslocamento por dia (GPS ou média do painel), separado pelos checks. */
    val dailyCommute: br.com.minhacorrida.core.km.DailyCommuteUseCase by lazy {
        br.com.minhacorrida.core.km.DailyCommuteUseCase(settings, kmSettings, kmLog, fuelLog, commuteChoices, logger)
    }
    /** Relatório .md do período (corridas, resultados, deduções, km e abastecimentos). */
    val rideReport: br.com.minhacorrida.core.report.RideReportUseCase by lazy {
        br.com.minhacorrida.core.report.RideReportUseCase(
            rideHistoryRepository, settings, goalSettings, kmSettings, kmLog, fuelLog, dailyCommute, logger,
            maintenanceLog = maintenanceLog, receipts = receiptPhotos,
        )
    }
    /** Ofertas lidas (só números, 60 dias, arquivo separado): mínimos × município. */
    val offerLogRepository: br.com.minhacorrida.core.market.OfferLogRepository by lazy {
        br.com.minhacorrida.infra.JsonLinesOfferLogRepository(context, logger)
    }
    val offerLog: br.com.minhacorrida.core.market.OfferLogUseCase by lazy {
        br.com.minhacorrida.core.market.OfferLogUseCase(offerLogRepository, logger)
    }
    /** Manutenções e fotos de comprovante (item 12). */
    val maintenanceLog: br.com.minhacorrida.core.receipt.MaintenanceLogRepository by lazy {
        br.com.minhacorrida.infra.SharedPrefsMaintenanceLog(context, logger)
    }
    val receiptPhotos: br.com.minhacorrida.infra.ReceiptPhotos by lazy { br.com.minhacorrida.infra.ReceiptPhotos(context, logger) }
    /** Destino da corrida: base, distância de "longe" e bairros a evitar (Configurações). */
    val destinationSettings: br.com.minhacorrida.core.destination.DestinationSettingsRepository by lazy {
        br.com.minhacorrida.infra.SharedPrefsDestinationSettings(context, logger)
    }
    /** Onde o carro ficou estacionado (início estimado do próximo trajeto). */
    val parking: br.com.minhacorrida.infra.ParkingStore by lazy { br.com.minhacorrida.infra.ParkingStore(context) }
    /** Trajetos do sensor de veículo: pendentes até o motorista Registrar ou Descartar. */
    val trips: br.com.minhacorrida.core.km.TripUseCase by lazy {
        br.com.minhacorrida.core.km.TripUseCase(
            br.com.minhacorrida.infra.SharedPrefsTripRepository(context, logger), kmLog, logger,
        )
    }
    val readingSession: ReadingSession = SharedPrefsReadingSession(context, logger)
    val parserRegistry = ParserRegistry(
        listOf(UberOfferParser(logger), InDriveOfferParser(logger)),
    )
    val analyzeScreen = AnalyzeScreenUseCase(parserRegistry, CalculateRideMetricsUseCase(), settings, logger)
    val formatter = RideAnalysisFormatter()
    /** Mesma regra do leitor de tela, usada pelo simulador da tela de configurações. */
    val evaluateRide = EvaluateRideUseCase(CalculateRideMetricsUseCase())
    /** Histórico de corridas verdes (uma instância para serviço e tela). */
    val rideHistoryRepository: RideHistoryRepository = JsonFileRideHistoryRepository(context, logger)
    val recordRide = RecordRideUseCase(rideHistoryRepository, settings, logger)
    /** Botão tocado no pedido do inDrive: refaz o card com o valor tocado (não grava). */
    val inDriveTap = br.com.minhacorrida.core.usecase.InDriveTapUseCase(evaluateRide, settings, logger)
    /** Grava só as corridas confirmadas pela tela do app e marca como realizadas no fim. */
    val rideLifecycle = br.com.minhacorrida.core.usecase.RideLifecycleUseCase(
        evaluateRide, settings, recordRide, rideHistoryRepository, logger,
    )
    val diagnosticsReport = br.com.minhacorrida.core.diagnostics.DiagnosticsReportFormatter()
    val rideHistory = RideHistoryUseCase(rideHistoryRepository, settings, logger)
    val historyFormatter = RideHistoryFormatter()
    val checkForUpdate = CheckForUpdateUseCase(
        GitHubReleaseSource(BuildConfig.RELEASE_API_URL, logger),
        BuildConfig.VERSION_CODE,
        logger,
    )
}
