package ca.andries.vpnmanager

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.util.*

class MainService : Service() {
    private lateinit var profileStore: ProfileStore

    private var toggleNotifEnabled = false
    private var disabledByNotification = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(RELOAD_FLAG, false) == true) {
            Log.i(javaClass.name, "Reload intent received")
            loadToggleNotifEnabled()
            resetToggle()
            profileStore.load(this)
            performCheck()
        }
        if (intent?.getBooleanExtra(TOGGLE_BY_NOTIF_FLAG, false) == true) {
            Log.i(javaClass.name, "Notif toggle intent received")
            disabledByNotification = !disabledByNotification
            if (disabledByNotification) {
                TunnelManager.shutdownTunnels(this, profileStore.getProfiles(), null)
                showToggleNotification(null)
            } else {
                performCheck()
            }
        }
        return START_STICKY
    }

    private fun performCheck() {
        val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connManager.allNetworkInfo.none { it.typeName != "VPN" && it.isConnectedOrConnecting }) {
            Log.i(javaClass.name, "Not connected, killing tunnel if connected")
            TunnelManager.toggleTunnel(this, profileStore.getProfiles(), null)
            return
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val teleManager = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val isWifiConnected = wifiManager.connectionInfo.ipAddress > 0
        val wifiName = wifiManager.connectionInfo.ssid.replace("(^\")|(\"$)".toRegex(), "")
        val carrierName = teleManager.simOperatorName

        var currentMatch: Map.Entry<Int, Profile>? = null
        var currentMatchPriority = 0

        for (profileEntry in profileStore.getProfiles().entries) {
            val profile = profileEntry.value
            if (!profile.enabled || profile.priority <= currentMatchPriority) continue

            val matched = if (isWifiConnected) {
                (profile.wifiRule == RuleMode.ALL && !profile.ssidExclList.contains(wifiName)) ||
                    (profile.wifiRule == RuleMode.SOME && profile.ssidInclList.contains(wifiName))
            } else {
                ((profile.mobileRule == RuleMode.ALL && !profile.carrierExclList.contains(carrierName)) ||
                    (profile.mobileRule == RuleMode.SOME && profile.carrierInclList.contains(carrierName)))
            }

            if (matched) {
                currentMatch = profileEntry
                currentMatchPriority = profile.priority
            }
        }

        Log.i(javaClass.name, "Profile check, match name = ${currentMatch?.value?.name} tunnel = ${currentMatch?.value?.tunnelName}")

        TunnelManager.toggleTunnel(this, profileStore.getProfiles(), currentMatch)

        if (currentMatch != null) {
            currentMatch.value.lastConnectionDate = Date().time
            profileStore.storeProfile(this, currentMatch.value, currentMatch.key)
            showToggleNotification(currentMatch.value.tunnelName)
        }
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chan = NotificationChannel(
                SERV_STATUS_NOTIF_CHANNEL_ID,
                getString(R.string.serv_status_notif_channel_desc),
                NotificationManager.IMPORTANCE_NONE
            )
            notifManager.createNotificationChannel(chan)
            startForeground(
                SERV_STATUS_NOTIF_ID,
                Notification.Builder(this, chan.id)
                    .setContentText(getString(R.string.serv_status_notif_text))
                    .setSmallIcon(R.drawable.ic_shield)
                    .setStyle(
                        Notification.BigTextStyle()
                            .bigText(getString(R.string.serv_status_notif_text))
                    )
                    .build()
            )
        }
    }

    private fun showToggleNotification(tunnelName: String?) {
        if (!toggleNotifEnabled) return

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                TOGGLE_NOTIF_CHANNEL_ID,
                getString(R.string.toggle_notif_channel_desc),
                NotificationManager.IMPORTANCE_LOW
            )
            notifManager.createNotificationChannel(chan)
        }
        val contentText = if (disabledByNotification) {
            getString(R.string.vpn_disconnected)
        } else {
            getString(R.string.vpn_connected, getString(R.string.wireguard_with_tunnel, tunnelName))
        }
        val pendingIntentIntent = Intent(this, MainBroadcastReceiver::class.java)
        pendingIntentIntent.putExtra(MainBroadcastReceiver.TOGGLE_FLAG, true)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, pendingIntentIntent, 0)
        val notif = NotificationCompat.Builder(this, TOGGLE_NOTIF_CHANNEL_ID)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .addAction(R.drawable.ic_baseline_toggle_on_24, getString(R.string.toggle_connection), pendingIntent)
            .build()
        notifManager.notify(TOGGLE_NOTIF_ID, notif)
    }

    private fun resetToggle() {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancel(TOGGLE_NOTIF_ID)
        disabledByNotification = false
    }

    private fun loadToggleNotifEnabled() {
        toggleNotifEnabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsActivity.SHOW_TOGGLE_NOTIF, false)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(javaClass.name, "Service created")

        loadToggleNotifEnabled()

        profileStore = ProfileStore(this)

        startForeground()

        val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val teleManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        connManager.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
            private fun onChange(event: String) {
                resetToggle()
                val wifiNet = wifiManager.connectionInfo.ssid
                val operatorName = teleManager.simOperatorName
                Log.i(javaClass.name, "Network $event! wifi: $wifiNet SIM operator: $operatorName")
                performCheck()
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                onChange("available")
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                onChange("lost")
            }
        })
    }

    companion object {
        private const val RELOAD_FLAG = "reload_flag"
        private const val TOGGLE_BY_NOTIF_FLAG = "toggle_notif"
        private const val SERV_STATUS_NOTIF_CHANNEL_ID = "serv_status"
        private const val TOGGLE_NOTIF_CHANNEL_ID = "toggle"
        private const val TOGGLE_NOTIF_ID = 13
        private const val SERV_STATUS_NOTIF_ID = 1

        fun reloadFromActivity(context: Context) {
            val intent = Intent(context, MainService::class.java)
            intent.putExtra(RELOAD_FLAG, true)
            context.startService(intent)
        }

        fun toggleByNotification(context: Context) {
            val intent = Intent(context, MainService::class.java)
            intent.putExtra(TOGGLE_BY_NOTIF_FLAG, true)
            context.startService(intent)
        }

        fun startService(context: Context) {
            val intent = Intent(context, MainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}