package com.example.kbowidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 라이브 경기 상황을 알림창에 고정(ongoing)하는 포그라운드 서비스.
 *
 * - 경기 전: "18:30 시작 예정" 표시, 3분 주기 폴링으로 라이브 전환 감지
 * - 라이브: 점수·이닝 + 한 줄 상황 바(주자·B·S·O·채널) + 현재 투/타, 30초 폴링
 * - 종료/취소: 최종 스코어로 알림을 남기고(고정 해제) 서비스 종료
 * - 오늘 경기 없음·연속 실패: 알림 제거 후 종료
 *
 * 시작은 MainActivity(토글 on + 오늘 경기 존재)에서만 — Android 12+의
 * 백그라운드 FGS 시작 제한을 피하기 위해 포그라운드 컨텍스트에서만 기동한다.
 */
class LiveNotificationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var polling: Runnable? = null
    private var failCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 즉시 startForeground (5초 제한) — 첫 데이터 도착 전 자리표시 알림
        startForeground(NOTI_ID, buildTextNotification("경기 정보 불러오는 중…", "", ongoing = true))
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        polling?.let { handler.removeCallbacks(it) }
        polling = null
        super.onDestroy()
    }

    private fun startPolling() {
        polling?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                pollOnce { delayMs ->
                    if (delayMs > 0) handler.postDelayed(polling ?: return@pollOnce, delayMs)
                }
            }
        }
        polling = r
        handler.post(r)
    }

    /** 한 사이클: 스코어 조회 → 알림 갱신 → 콜백으로 다음 폴링 지연(0=중단) 전달 */
    private fun pollOnce(next: (Long) -> Unit) {
        val prefs = KboCommon.prefs(this)
        val away = prefs.getString("app_sched_away", "") ?: ""
        val home = prefs.getString("app_sched_home", "") ?: ""
        val today = KboCommon.getGameDate()
        val schedDate = prefs.getString("app_sched_date_key", "") ?: ""
        if (away.isEmpty() || home.isEmpty() || schedDate != today ||
            prefs.getBoolean("app_sched_is_future", false)) {
            stopSelfRemoving()
            return
        }

        KboCommon.httpClient.newCall(
            Request.Builder().url("$BASE/api/scores").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onPollError(next) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: return onPollError(next)
                    val scores = JSONObject(body).getJSONArray("scores")
                    var game: JSONObject? = null
                    for (i in 0 until scores.length()) {
                        val s = scores.getJSONObject(i)
                        if ((s.getString("away") == away && s.getString("home") == home) ||
                            (s.getString("away") == home && s.getString("home") == away)) {
                            game = s
                            break
                        }
                    }
                    failCount = 0
                    handler.post { render(game, prefs.getString("app_sched_time", "") ?: "", next) }
                } catch (e: Exception) { onPollError(next) }
            }
        })
    }

    private fun onPollError(next: (Long) -> Unit) {
        failCount++
        handler.post {
            if (failCount >= 10) stopSelfRemoving()   // ~5분 연속 실패 시 포기
            else next(POLL_LIVE_MS)
        }
    }

    private fun render(game: JSONObject?, startTime: String, next: (Long) -> Unit) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (game?.optString("status")) {
            "1" -> {
                nm.notify(NOTI_ID, buildLiveNotification(game))
                next(POLL_LIVE_MS)
            }
            "2" -> {
                // 최종 스코어를 일반 알림으로 남기고 종료 (알림은 스와이프로 지울 수 있게)
                finishWith(buildFinalNotification(game))
            }
            "3" -> finishWith(buildTextNotification(
                "${game.getString("away")} vs ${game.getString("home")} 경기취소",
                game.optString("inning", "우천취소"), ongoing = false))
            else -> {
                // 경기 전(0) 또는 스코어 목록에 아직 없음 — 시작 예정 표시 후 저빈도 폴링
                nm.notify(NOTI_ID, buildTextNotification(
                    "오늘 ${startTime.ifEmpty { "저녁" }} 경기 시작 예정",
                    "시작하면 실시간 스코어로 바뀝니다", ongoing = true))
                next(POLL_PRE_MS)
            }
        }
    }

    // ── 알림 빌더들 ──

    private fun buildLiveNotification(s: JSONObject): Notification {
        val away = s.getString("away"); val home = s.getString("home")
        val awayScore = s.optString("away_score", "-")
        val homeScore = s.optString("home_score", "-")
        val inning = s.optString("inning", "")
        val title = "$away $awayScore : $homeScore $home"

        val rv = RemoteViews(packageName, R.layout.notification_live)
        rv.setTextViewText(R.id.tv_n_score, title)
        rv.setTextViewText(R.id.tv_n_inning, "● $inning")
        val bases = s.optJSONArray("bases")
        if (bases != null && s.optInt("balls", -1) >= 0) {
            val prefs = KboCommon.prefs(this)
            rv.setImageViewBitmap(R.id.iv_n_bar, SituationDrawer.makeWidgetBar(
                inning,
                prefs.getString("live_ch_name", "") ?: "",
                prefs.getString("live_ch_num", "") ?: "",
                booleanArrayOf(bases.optBoolean(0), bases.optBoolean(1), bases.optBoolean(2)),
                s.optInt("balls", 0), s.optInt("strikes", 0), s.optInt("outs", 0)))
            rv.setViewVisibility(R.id.iv_n_bar, android.view.View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.iv_n_bar, android.view.View.GONE)
        }
        // 현재 투/타 — 앱 폴링이 prefs에 남긴 캐시를 재사용 (추가 요청 없음)
        rv.setTextViewText(R.id.tv_n_players, currentPlayersText())

        return baseBuilder(ongoing = true)
            .setContentTitle(title)
            .setContentText("● $inning")
            .setCustomBigContentView(rv)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
    }

    private fun buildFinalNotification(s: JSONObject): Notification =
        baseBuilder(ongoing = false)
            .setContentTitle("${s.getString("away")} ${s.optString("away_score")} : " +
                             "${s.optString("home_score")} ${s.getString("home")}")
            .setContentText("경기종료")
            .build()

    private fun buildTextNotification(title: String, text: String, ongoing: Boolean): Notification =
        baseBuilder(ongoing).setContentTitle(title).setContentText(text).build()

    private fun baseBuilder(ongoing: Boolean): NotificationCompat.Builder {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
    }

    /** 앱이 prefs에 캐시한 투수/타자 JSON([{label,name}...])에서 "투 조병현 · 타 곽도규" 생성 */
    private fun currentPlayersText(): String {
        return try {
            val prefs = KboCommon.prefs(this)
            val parts = mutableListOf<String>()
            for (key in listOf("app_away_pitchers", "app_home_pitchers")) {
                val arr = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val label = o.optString("label"); val name = o.optString("name")
                    if (label in listOf("투", "타") && name.isNotEmpty()) parts.add("$label $name")
                }
            }
            parts.joinToString(" · ")
        } catch (_: Exception) { "" }
    }

    private fun finishWith(finalNoti: Notification) {
        polling?.let { handler.removeCallbacks(it) }
        // DETACH: 포그라운드 해제하되 알림은 남긴다 → 최종 스코어가 지워지지 않고 표시됨
        stopForeground(STOP_FOREGROUND_DETACH)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_ID, finalNoti)
        stopSelf()
    }

    private fun stopSelfRemoving() {
        polling?.let { handler.removeCallbacks(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "라이브 스코어 고정 알림", NotificationManager.IMPORTANCE_LOW)
            ch.description = "경기 중 점수·이닝·주자 상황을 알림창에 고정 표시"
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    companion object {
        private const val BASE = "https://web-production-6aae76.up.railway.app"
        private const val CHANNEL_ID = "kbo_live_score"
        private const val NOTI_ID = 1001
        private const val POLL_LIVE_MS = 30_000L
        private const val POLL_PRE_MS = 180_000L

        /** 토글 on + 오늘 경기 존재 시 포그라운드 컨텍스트(MainActivity)에서 호출 */
        fun startIfEnabled(context: Context) {
            val prefs = KboCommon.prefs(context)
            if (!prefs.getBoolean("live_noti", false)) return
            val schedDate = prefs.getString("app_sched_date_key", "") ?: ""
            if (schedDate != KboCommon.getGameDate()) return
            // 지금 표시 중인 일정이 내일/다음 경기(휴식일·브레이크)면 오늘 띄울 게 없다
            if (prefs.getBoolean("app_sched_is_future", false)) return
            // 이미 종료(2)/취소(3)로 확정된 날엔 새로 띄우지 않음
            val st = prefs.getString("app_status", "") ?: ""
            if (st == "2" || st == "3") return
            try {
                val intent = Intent(context, LiveNotificationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                println("[라이브알림] 서비스 시작 실패: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveNotificationService::class.java))
        }
    }
}
