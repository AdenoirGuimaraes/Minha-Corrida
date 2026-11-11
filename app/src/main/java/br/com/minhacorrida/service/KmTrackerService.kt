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
import android.os.IBinder
import android.os.Looper
import br.com.minhacorrida.MinhaCorridaApp
import br.com.minhacorrida.R
import br.com.minhacorrida.core.km.DistanceAccumulator
import br.com.minhacorrida.core.km.GeoPoint
import br.com.minhacorrida.ui.MainActivity
import java.time.LocalDate

/**
 * Conta os km rodados de carro pelo GPS enquanto o "Controle de km" estiver ligado.
 * Serviço em primeiro plano (o Android exige uma notificação fixa para usar o GPS com a
 * tela apagada). A notificação mostra os km do dia e tem o botão "Parar".
 * Os metros vão para o [br.com.minhacorrida.core.km.KmLogRepository] a cada ~200 m.
 */
class KmTrackerService : Service(), LocationListener {

    private val container by lazy { (application as MinhaCorridaApp).container }
    private val accumulator = DistanceAccumulator()
    private var pendingMeters = 0.0
    private var lastNotifiedKm = -1.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.kmSettings.setKmTrackingEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!container.kmSettings.isKmTrackingEnabled() || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startInForeground(notification(todayKm()))
            val manager = getSystemService(LocationManager::class.java)
            manager.removeUpdates(this)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper())
            container.logger.debug(TAG, "Contagem de km ligada")
        } catch (e: SecurityException) {
            container.logger.error(TAG, "Sem permissão de localização", e)
            stopSelf()
        } catch (e: Exception) {
            container.logger.error(TAG, "Falha ao ligar o GPS", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        try {
            val meters = accumulator.add(
                GeoPoint(location.latitude, location.longitude, location.accuracy, location.time),
            )
            if (meters <= 0) return
            pendingMeters += meters
            if (pendingMeters >= FLUSH_METERS) flush()
        } catch (e: Exception) {
            container.logger.error(TAG, "Falha ao somar posição do GPS", e)
        }
    }

    override fun onDestroy() {
        try {
            getSystemService(LocationManager::class.java).removeUpdates(this)
            flush()
        } catch (e: Exception) {
            container.logger.error(TAG, "Falha ao desligar o GPS", e)
        }
        super.onDestroy()
    }

    /** Grava os metros pendentes e atualiza a notificação (a cada 0,5 km). */
    private fun flush() {
        if (pendingMeters <= 0) return
        container.kmLog.addMeters(LocalDate.now(), pendingMeters)
        pendingMeters = 0.0
        val km = todayKm()
        if (km - lastNotifiedKm >= 0.5) {
            lastNotifiedKm = km
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(km))
        }
    }

    private fun todayKm(): Double = container.kmLog.kmOn(LocalDate.now()).toDouble()

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(km: Double): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.km_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KmTrackerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.km_notification_title))
            .setContentText(getString(R.string.km_notification_text, String.format("%.1f", km).replace('.', ',')))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, getString(R.string.km_notification_stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "KmTracker"
        private const val CHANNEL_ID = "km_tracker"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "br.com.minhacorrida.KM_STOP"
        private const val MIN_TIME_MS = 4_000L
        private const val MIN_DISTANCE_M = 15f
        private const val FLUSH_METERS = 200.0

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Liga (se a opção estiver ligada e houver permissão) ou desliga a contagem. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, KmTrackerService::class.java)
            try {
                if (enabled && hasLocationPermission(context)) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("MC/$TAG", "Falha ao ligar/desligar a contagem de km", e)
            }
        }
    }
}
