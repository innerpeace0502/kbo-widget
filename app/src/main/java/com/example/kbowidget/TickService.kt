package com.example.kbowidget

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.*

class TickService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable
    private var tickCount = 0  // 1초마다 증가, 60초 주기 슬림 갱신용

    override fun onCreate() {
        super.onCreate()
        runnable = object : Runnable {
            override fun run() {
                updateClock()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)

        // 서비스가 종료되면 즉시 재시작
        val restart = Intent(applicationContext, TickService::class.java)
        applicationContext.startService(restart)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateClock() {
        val awm = AppWidgetManager.getInstance(this)

        // 일반 위젯 ID (시계 있음)
        val regularIds = awm.getAppWidgetIds(
            ComponentName(this, KboWidgetProvider::class.java)
        )
        // 슬림 위젯 ID (시계 없음)
        val slimIds = awm.getAppWidgetIds(
            ComponentName(this, KboWidgetProviderSlim::class.java)
        )

        // 위젯이 하나도 없으면 서비스 종료
        if (regularIds.isEmpty() && slimIds.isEmpty()) {
            stopSelf()
            return
        }

        val now  = Calendar.getInstance()
        val hm   = SimpleDateFormat("HH:mm", Locale.KOREA).format(now.time)
        val sec  = ":" + String.format("%02d", now.get(Calendar.SECOND))
        val date = SimpleDateFormat("yyyy.MM.dd EEE", Locale.KOREA).format(now.time)

        // 새벽 4시 기준으로 날짜 변경 감지
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val gameDate = if (hour < 4) {
            val yesterday = Calendar.getInstance()
            yesterday.add(Calendar.DAY_OF_MONTH, -1)
            SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(yesterday.time)
        } else {
            SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(now.time)
        }

        val prefs     = getSharedPreferences("kbo_prefs", MODE_PRIVATE)
        val savedDate = prefs.getString("last_date", "")

        if (savedDate != gameDate) {
            prefs.edit().putString("last_date", gameDate).apply()
            KboWidgetProvider.logoCache.clear()
            // ✅ 날짜 변경 시 슬림 위젯 상태 초기화
            prefs.edit()
                .remove("slim_status")
                .remove("slim_away_score")
                .remove("slim_home_score")
                .remove("slim_stadium")
                .remove("slim_date")
                .apply()
            // 일반 위젯 갱신
            val fetchIntent = Intent(this, KboWidgetProvider::class.java).apply {
                action = KboWidgetProvider.ACTION_FETCH
            }
            sendBroadcast(fetchIntent)
            // 슬림 위젯 갱신 — onUpdate 직접 트리거
            if (slimIds.isNotEmpty()) {
                val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = ComponentName(applicationContext, KboWidgetProviderSlim::class.java)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, slimIds)
                }
                sendBroadcast(updateIntent)
            }
        }

        // ✅ 60초마다 슬림 위젯 갱신 — ACTION_APPWIDGET_UPDATE로 onUpdate 직접 트리거
        tickCount++
        if (tickCount >= 60 && slimIds.isNotEmpty()) {
            tickCount = 0
            val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = ComponentName(applicationContext, KboWidgetProviderSlim::class.java)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, slimIds)
            }
            sendBroadcast(updateIntent)
        }

        // 일반 위젯만 시계 업데이트 (슬림 위젯은 시간/날짜 표시 없음)
        regularIds.forEach { id ->
            val v = RemoteViews(packageName, R.layout.widget_layout)
            v.setTextViewText(R.id.tv_time_hm,  hm)
            v.setTextViewText(R.id.tv_time_sec, sec)
            v.setTextViewText(R.id.tv_date,     date)
            awm.partiallyUpdateAppWidget(id, v)
        }
    }
}