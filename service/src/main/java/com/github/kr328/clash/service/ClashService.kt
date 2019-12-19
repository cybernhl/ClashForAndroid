package com.github.kr328.clash.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.event.*
import com.github.kr328.clash.core.utils.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class ClashService : Service(), IClashEventObserver {
    private val executor = Executors.newSingleThreadExecutor()

    private val instance: ClashServiceImpl by lazy {
        ClashServiceImpl(this)
    }

    private val events: ClashEventService
        get() = instance.eventService
    private val clash: Clash
        get() = instance.clash

    //private lateinit var puller: ClashEventPuller
    private lateinit var notification: ClashNotification

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON ->
                    instance.eventService.registerEventObserver(
                        ClashService::class.java.name,
                        this@ClashService,
                        intArrayOf(Event.EVENT_TRAFFIC)
                    )
                Intent.ACTION_SCREEN_OFF ->
                    instance.eventService.registerEventObserver(
                        ClashService::class.java.name,
                        this@ClashService,
                        intArrayOf()
                    )
            }
        }
    }

    private fun onClashProcessChanged(event: ProcessEvent) {
        instance.eventService.performProcessEvent(event)
    }

    fun acquireEvent(event: Int) {
        if ( instance.clash.getCurrentProcessStatus() == ProcessEvent.STOPPED )
            return

        when ( event ) {
            Event.EVENT_BANDWIDTH ->
                instance.eventPoll.startBandwidthPoll()
            Event.EVENT_TRAFFIC ->
                instance.eventPoll.startTrafficPoll()
            Event.EVENT_LOG ->
                instance.eventPoll.startLogsPoll()
        }
    }

    fun releaseEvent(event: Int) {
        if ( instance.clash.getCurrentProcessStatus() == ProcessEvent.STOPPED )
            return

        when ( event ) {
            Event.EVENT_BANDWIDTH ->
                instance.eventPoll.stopBandwidthPoll()
            Event.EVENT_TRAFFIC ->
                instance.eventPoll.stopTrafficPoll()
            Event.EVENT_LOG ->
                instance.eventPoll.stopLogPoll()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Init instance
        instance

        notification = ClashNotification(this)

        instance.eventService.registerEventObserver(
            ClashService::class.java.name,
            this@ClashService,
            intArrayOf(Event.EVENT_TRAFFIC)
        )

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        notification.setVpn(false)
        notification.show()

        instance.clash.start()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return instance
    }

    override fun onDestroy() {
        instance.clash.stop()

        instance.shutdown()

        executor.shutdown()

        unregisterReceiver(screenReceiver)

        super.onDestroy()
    }

    override fun onProfileChanged(event: ProfileChangedEvent?) {
        reloadProfile()
    }

    override fun onProcessEvent(event: ProcessEvent?) {
        when (event!!) {
            ProcessEvent.STARTED -> {
                reloadProfile()

                notification.show()

                instance.eventService.recastEventRequirement()
            }
            ProcessEvent.STOPPED -> {
                instance.eventService.performSpeedEvent(TrafficEvent(0, 0))
                instance.eventService.performBandwidthEvent(BandwidthEvent(0))

                notification.cancel()

                stopSelf()
            }
        }

        sendBroadcast(Intent(Constants.CLASH_PROCESS_BROADCAST_ACTION).setPackage(packageName))
    }

    private fun reloadProfile() {
        executor.submit {
            if ( clash.getCurrentProcessStatus() != ProcessEvent.STARTED)
                return@submit

            val active = instance.profileService.queryActiveProfile()

            if (active == null) {
                events.performErrorEvent(ErrorEvent(ErrorEvent.Type.PROFILE_LOAD, "No active profile"))
                clash.stop()
                return@submit
            }

            Log.i("Loading profile ${active.file}")

            try {
                clash.loadProfile(active.file)

                instance.profileService.queryProfileSelected(active.id).mapNotNull {
                    if ( clash.setSelectedProxy(it.key, it.value) )
                        null
                    else
                        it.key
                }.toList().apply {
                    instance.profileService.removeCurrentProfileProxy(this)
                }

                notification.setProfile(active.name)

                events.performProfileReloadEvent(ProfileReloadEvent())
            } catch (e: Exception) {
                clash.stop()
                events.performErrorEvent(ErrorEvent(ErrorEvent.Type.PROFILE_LOAD, e.message ?: e.toString()))
                Log.w("Load profile failure", e)
            }
        }
    }

    override fun onProfileReloaded(event: ProfileReloadEvent?) {
        sendBroadcast(Intent(Constants.CLASH_RELOAD_BROADCAST_ACTION).setPackage(packageName))
    }

    override fun onTrafficEvent(event: TrafficEvent?) {
        notification.setSpeed(event?.up ?: 0, event?.down ?: 0)
    }

    override fun onBandwidthEvent(event: BandwidthEvent?) {}
    override fun onLogEvent(event: LogEvent?) {}
    override fun onErrorEvent(event: ErrorEvent?) {}
    override fun asBinder(): IBinder = object : Binder() {
        override fun queryLocalInterface(descriptor: String): IInterface? {
            return this@ClashService
        }
    }
}