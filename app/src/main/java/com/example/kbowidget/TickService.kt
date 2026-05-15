package com.example.kbowidget

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 메인 앱이 살아있을 때만 동작하는 보조 갱신 서비스.
 *
 * - 시계 갱신은 더 이상 담당하지 않음 (TextClock이 자동 갱신)
 * - 60초마다 두 위젯 모두 갱신 (알람 체인 백업)
 * - 04:00 게임 날짜 변경 시 캐시 클리어 + 위젯 갱신 트리거
 *
 * Android 8+ 백그라운드 제한으로 앱 프로세스가 죽으면 이 서비스도 죽음.
 * 위젯 자동 갱신의 주된 메커니즘은 AlarmManager 체인 (KboWidgetProvider.scheduleFetchAlarm).
 */
class TickService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable

    override fun onCreate() {
        super.onCreate()
        runnable = object : Runnable {
            override fun run() {
                tick()
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        handler.post(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        // 백그라운드 startService 제한 회피 — Android 8+에서는 무시될 수 있음
        try { applicationContext.startService(Intent(applicationContext, TickService::class.java)) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        val awm = AppWidgetManager.getInstance(this)
        val regularIds = awm.getAppWidgetIds(ComponentName(this, KboWidgetProvider::class.java))
        val slimIds    = awm.getAppWidgetIds(ComponentName(this, KboWidgetProviderSlim::class.java))

        if (regularIds.isEmpty() && slimIds.isEmpty()) { stopSelf(); return }

        // 04:00 게임 날짜 변경 → 캐시 일괄 클리어 + 위젯 강제 갱신
        if (KboCommon.clearCacheIfDateChanged(this)) {
            KboWidgetProvider.logoCache.clear()
            KboCommon.triggerWidgetRefresh(this)
            return
        }

        // 60초마다 양쪽 위젯 갱신 (알람 체인 보완)
        KboCommon.triggerWidgetRefresh(this)
    }

    companion object {
        private const val INTERVAL_MS = 60_000L  // 60초
    }
}
