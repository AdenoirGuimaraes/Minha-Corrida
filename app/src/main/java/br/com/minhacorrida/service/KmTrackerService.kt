package br.com.minhacorrida.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.R
import br.com.minhacorrida.core.km.DistanceAccumulator
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.core.km.Trip
import br.com.minhacorrida.core.km.TripOutcome
import br.com.minhacorrida.ui.MainActivity
import java.util.UUID

/**
 * Um TRAJETO de carro: liga o GPS quando o sensor detecta "em veículo"
 * ([VehicleTransitionReceiver]) e fecha quando o motorista sai do veículo, toca em
 * "Encerrar trajeto" ou fica [IDLE_TIMEOUT_MS] parado. Ao fechar, o [br.com.minhacorrida.core.km.TripUseCase]
 * decide: curto → ignora; com corrida → registra; senão → notificação Registrar/Descartar.
 *
 * Serviço em primeiro plano (exigência do Android para usar o GPS com a tela apagada).
 * O trajeto em andamento fica salvo: se o Android matar o serviço, ele continua de onde parou.
 */
class KmTrackerService : Service(), LocationListener {

    private val container by lazy { (application as MinhaCorridaApp).container }
    private val prefs by lazy { getSharedPreferences(STATE_PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val accumulator = DistanceAccumulator()
    private var tripId: String? = null
    private var tripStart = 0L
    private var tripMeters = 0.0
    private var lastMovementAt = 0L
    private var lastNotifiedMeters = 0.0

    /** Fecha o trajeto se o carro ficou parado tempo demais (saída do veículo não detectada). */
    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastMovementAt > IDLE_TIMEOUT_MS) {
                finishTrip()
            } else {
                handler.postDelayed(this, IDLE_CHECK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_END -> {
                    if (tripId == null && !restoreTrip()) stopSelf() else finishTrip()
                    START_NOT_STICKY
                }
                else -> {
                    // ACTION_START, ou recriado pelo Android (intent null): começa ou retoma.
                    if (!container.kmSettings.isKmTrackingEnabled() || !hasLocationPermission(this)) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    if (tripId == null && !restoreTrip()) beginTrip()
                    startInForeground(progressNotification())
                    startGps()
                    START_STICKY
                }
            }
        } catch (e: Exception) {
            container.logger.error(TAG, "Falha no serviço de trajeto", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun beginTrip() {
        tripId = UUID.randomUUID().toString()
        tripStart = System.currentTimeMillis()
        tripMeters = 0.0
        lastMovementAt = tripStart
        accumulator.reset()
        saveState()
        container.logger.debug(TAG, "Trajeto iniciado")
    }

    /** Retoma o trajeto salvo (serviço recriado). @return true se havia um. */
    private fun restoreTrip(): Boolean {
        val id = prefs.getString(KEY_ID, null) ?: return false
        tripId = id
        tripStart = prefs.getLong(KEY_START, System.currentTimeMillis())
        tripMeters = prefs.getFloat(KEY_METERS, 0f).toDouble()
        lastMovementAt = System.currentTimeMillis()
        return true
    }

    private fun saveState() {
        prefs.edit().putString(KEY_ID, tripId).putLong(KEY_START, tripStart).putFloat(KEY_METERS, tripMeters.toFloat()).apply()
    }

    private fun startGps() {
        try {
            val manager = getSystemService(LocationManager::class.java)
            manager.removeUpdates(this)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper())
            handler.removeCallbacks(idleCheck)
            handler.postDelayed(idleCheck, IDLE_CHECK_MS)
        } catch (e: SecurityException) {
            container.logger.error(TAG, "Sem permissão de localização", e)
            finishTrip()
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            val meters = accumulator.add(GeoPoint(location.latitude, location.longitude, location.accuracy, location.time))
            if (meters <= 0) return
            tripMeters += meters
            lastMovementAt = System.currentTimeMillis()
            saveState()
            if (tripMeters - lastNotifiedMeters >= NOTIFY_EVERY_METERS) {
                lastNotifiedMeters = tripMeters
                getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification())
            }
        } catch (e: Exception) {
            container.logger.error(TAG, "Falha ao somar posição do GPS", e)
        }
    }

    /** Fecha o trajeto, decide (ignorar / registrar / perguntar) e encerra o serviço. */
    private fun finishTrip() {
        handler.removeCallbacks(idleCheck)
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        val id = tripId
        val start = tripStart
        val meters = tripMeters
        val end = System.currentTimeMillis()
        tripId = null
        prefs.edit().clear().apply()
        if (id != null) {
            Thread {
                try {
                    val hadRide = container.rideHistoryRepository.between(start, end).isNotEmpty()
                    when (val outcome = container.trips.finish(id, start, end, meters, hadRide)) {
                        is TripOutcome.NeedsDecision -> notifyDecision(outcome.trip)
                        is TripOutcome.AutoKept -> container.logger.debug(TAG, "Trajeto com corrida registrado: ${outcome.trip.km} km")
                        is TripOutcome.TooShort -> container.logger.debug(TAG, "Trajeto curto ignorado")
                    }
                } catch (e: Exception) {
                    container.logger.error(TAG, "Falha ao fechar o trajeto", e)
                }
            }.start()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        super.onDestroy()
    }

    // ---- Notificações ----

    private fun channel(id: String, nameRes: Int, importance: Int) {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, getString(nameRes), importance))
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun progressNotification(): Notification {
        channel(CHANNEL_PROGRESS, R.string.km_channel, NotificationManager.IMPORTANCE_LOW)
        val end = PendingIntent.getService(
            this, 1, Intent(this, KmTrackerService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.km_notification_title))
            .setContentText(getString(R.string.km_notification_text, km(tripMeters)))
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, getString(R.string.km_notification_stop), end).build())
            .build()
    }

    /** "🚗 Trajeto de 12,4 km · 07:42–08:05" com Registrar e Descartar. */
    private fun notifyDecision(trip: Trip) {
        channel(CHANNEL_DECISION, R.string.trip_channel, NotificationManager.IMPORTANCE_DEFAULT)
        fun action(action: String, label: Int, code: Int): Notification.Action {
            val intent = Intent(this, TripActionReceiver::class.java)
                .setAction(action)
                .putExtra(TripActionReceiver.EXTRA_TRIP_ID, trip.id)
            val pending = PendingIntent.getBroadcast(
                this, trip.id.hashCode() + code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(null as android.graphics.drawable.Icon?, getString(label), pending).build()
        }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
        val notification = Notification.Builder(this, CHANNEL_DECISION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.trip_notification_title, km(trip.meters)))
            .setContentText(
                getString(
                    R.string.trip_notification_text,
                    time.format(java.util.Date(trip.startMillis)), time.format(java.util.Date(trip.endMillis)),
                ),
            )
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addAction(action(TripActionReceiver.ACTION_KEEP, R.string.trip_keep, 1))
            .addAction(action(TripActionReceiver.ACTION_DISCARD, R.string.trip_discard, 2))
            .build()
        getSystemService(NotificationManager::class.java).notify(trip.id.hashCode(), notification)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    private fun km(meters: Double) = String.format(java.util.Locale("pt", "BR"), "%.1f", meters / 1000)

    companion object {
        private const val TAG = "KmTracker"
        private const val CHANNEL_PROGRESS = "km_tracker"
        private const val CHANNEL_DECISION = "km_trips"
        private const val PROGRESS_ID = 42
        private const val ACTION_START = "br.com.minhacorrida.TRIP_START"
        private const val ACTION_END = "br.com.minhacorrida.TRIP_END"
        private const val MIN_TIME_MS = 4_000L
        private const val MIN_DISTANCE_M = 15f
        private const val NOTIFY_EVERY_METERS = 500.0
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val IDLE_CHECK_MS = 60_000L
        private const val STATE_PREFS = "minha_corrida_current_trip"
        private const val KEY_ID = "id"
        private const val KEY_START = "start"
        private const val KEY_METERS = "meters"

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Entrou no veículo: começa um trajeto (GPS ligado). */
        fun startTrip(context: Context) {
            if (!hasLocationPermission(context)) return
            try {
                context.startForegroundService(Intent(context, KmTrackerService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                // Ex.: Android não deixou ligar em segundo plano (falta "Permitir o tempo todo").
                android.util.Log.e("MC/$TAG", "Não foi possível começar o trajeto", e)
            }
        }

        /** Saiu do veículo (ou opção desligada): fecha o trajeto em andamento, se houver. */
        fun endTrip(context: Context) {
            try {
                context.startService(Intent(context, KmTrackerService::class.java).setAction(ACTION_END))
            } catch (e: Exception) {
                // Serviço não estava rodando: não há trajeto para fechar.
                android.util.Log.d("MC/$TAG", "Nenhum trajeto em andamento para fechar")
            }
        }
    }
}
