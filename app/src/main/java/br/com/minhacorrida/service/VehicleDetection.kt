package br.com.minhacorrida.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import br.com.minhacorrida.MinhaCorridaApp
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Sensor "em veículo" do Android (Activity Recognition): avisa quando o motorista entra e sai
 * de um veículo. Gasta pouquíssima bateria (acelerômetro); o GPS só liga dentro do carro.
 */
object VehicleDetection {
    private const val TAG = "MC/VehicleDetection"

    fun hasActivityPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** "Permitir o tempo todo": necessário para o GPS ligar com o app fechado. */
    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, Intent(context, VehicleTransitionReceiver::class.java), flags)
    }

    /** Liga (opção ligada e permissões dadas) ou desliga o sensor de veículo. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context, enabled: Boolean) {
        try {
            val client = ActivityRecognition.getClient(context)
            if (enabled && hasActivityPermission(context)) {
                val transitions = listOf(
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                ).map {
                    ActivityTransition.Builder()
                        .setActivityType(DetectedActivity.IN_VEHICLE)
                        .setActivityTransition(it)
                        .build()
                }
                client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
                    .addOnFailureListener { android.util.Log.e(TAG, "Sensor de veículo recusado", it) }
            } else {
                client.removeActivityTransitionUpdates(pendingIntent(context))
                KmTrackerService.endTrip(context)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Falha ao ligar/desligar o sensor de veículo", e)
        }
    }
}

/** Entrou no veículo → começa o trajeto (GPS); saiu → fecha o trajeto. */
class VehicleTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (!ActivityTransitionResult.hasResult(intent)) return
            val settings = (context.applicationContext as MinhaCorridaApp).container.kmSettings
            if (!settings.isKmTrackingEnabled()) return
            val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
            if (last.activityType != DetectedActivity.IN_VEHICLE) return
            if (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                KmTrackerService.startTrip(context)
            } else {
                KmTrackerService.endTrip(context)
            }
        } catch (e: Exception) {
            android.util.Log.e("MC/VehicleTransition", "Falha ao tratar entrada/saída do veículo", e)
        }
    }
}

/** Botões Registrar/Descartar da notificação do trajeto. */
class TripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TRIP_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                val container = (context.applicationContext as MinhaCorridaApp).container
                if (intent.action == ACTION_KEEP) {
                    container.trips.keep(id)
                } else {
                    container.trips.discard(id)
                    container.parking.revertIfFrom(id) // outro carro: o seu continua onde estava
                }
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
            } catch (e: Exception) {
                android.util.Log.e("MC/TripAction", "Falha ao decidir o trajeto", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_KEEP = "br.com.minhacorrida.TRIP_KEEP"
        const val ACTION_DISCARD = "br.com.minhacorrida.TRIP_DISCARD"
        const val EXTRA_TRIP_ID = "trip_id"
    }
}

/** Celular reiniciado: o Android esquece o sensor de veículo; registra de novo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val settings = (context.applicationContext as MinhaCorridaApp).container.kmSettings
            if (settings.isKmTrackingEnabled()) VehicleDetection.sync(context, true)
        } catch (e: Exception) {
            android.util.Log.e("MC/Boot", "Falha ao religar o sensor de veículo", e)
        }
    }
}
