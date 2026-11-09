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
    val settings: SettingsRepository = SharedPrefsSettingsRepository(context.applicationContext)
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
