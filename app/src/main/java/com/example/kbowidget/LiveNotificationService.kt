package com.example.kbowidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
 * - 라이브: 점수(중앙)·[이닝|주자|B·S·O] 바·현재 투/타 — 30초 폴링, 완전 커스텀 뷰
 *   (Decorated 스타일의 아이콘 열 들여쓰기 때문에 중앙정렬이 어긋나던 문제 해결)
 * - 접힌 상태에도 점수·이닝·아웃/주자 요약을 커스텀 한 줄로 표시
 * - 사용자가 알림을 지우면(안드14+ 허용) deleteIntent로 즉시 재표시
 * - 종료/취소: 최종 스코어를 일반 알림으로 남기고(고정 해제) 서비스 종료
 * - 매치업은 앱 캐시(app_sched_*)를 우선 쓰되, 없으면 서버에서 직접 조회 —
 *   앱을 열지 않은 날에도 위젯/알람 경로로 자동 기동 가능
 * - 30분 이상 갱신 실패(도즈 등)면 stale 표시를 남기지 않도록 자동 종료
 */
class LiveNotificationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var polling: Runnable? = null
    private var failCount = 0
    private var lastOkMs = 0L
    private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 즉시 startForeground (5초 제한) — 재소환(REPOST)이면 마지막 화면 그대로 복귀
        startForeground(NOTI_ID, lastNotification
            ?: buildTextNotification("경기 정보 불러오는 중…", "", ongoing = true))
        if (intent?.action != ACTION_REPOST || polling == null) startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        polling?.let { handler.removeCallbacks(it) }
        polling = null
        super.onDestroy()
    }

    private fun startPolling() {
        polling?.let { handler.removeCallbacks(it) }
        lastOkMs = System.currentTimeMillis()
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

    /** 오늘의 매치업 (away, home, time) — 앱 캐시 → 자체 캐시 → 서버 조회 순 */
    private fun resolveMatchup(done: (Triple<String, String, String>?) -> Unit) {
        val prefs = KboCommon.prefs(this)
        val today = KboCommon.getGameDate()

        val appAway = prefs.getString("app_sched_away", "") ?: ""
        val appHome = prefs.getString("app_sched_home", "") ?: ""
        if (appAway.isNotEmpty() && appHome.isNotEmpty() &&
            prefs.getString("app_sched_date_key", "") == today &&
            !prefs.getBoolean("app_sched_is_future", false)) {
            done(Triple(appAway, appHome, prefs.getString("app_sched_time", "") ?: ""))
            return
        }
        if (prefs.getString("noti_sched_date", "") == today) {
            val a = prefs.getString("noti_sched_away", "") ?: ""
            val h = prefs.getString("noti_sched_home", "") ?: ""
            if (a.isEmpty()) { done(null); return }  // 오늘 경기 없음으로 확인된 캐시
            done(Triple(a, h, prefs.getString("noti_sched_time", "") ?: ""))
            return
        }

        val team = prefs.getString("team", "전체") ?: "전체"
        val teamParam = if (team == "전체") "" else "?team=$team"
        KboCommon.httpClient.newCall(
            Request.Builder().url("$BASE/api/schedule/today$teamParam").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { handler.post { done(null) } }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val games = json.optJSONArray("경기목록")
                    val future = json.optBoolean("내일경기", false) ||
                                 json.optBoolean("다음경기", false) ||
                                 json.optBoolean("올스타브레이크", false)
                    val editor = KboCommon.prefs(this@LiveNotificationService).edit()
                        .putString("noti_sched_date", today)
                    if (games == null || games.length() == 0 || future) {
                        editor.putString("noti_sched_away", "").apply()
                        handler.post { done(null) }
                    } else {
                        val g = games.getJSONObject(0)
                        val a = g.getString("away"); val h = g.getString("home")
                        val t = g.optString("time", "")
                        editor.putString("noti_sched_away", a)
                            .putString("noti_sched_home", h)
                            .putString("noti_sched_time", t).apply()
                        handler.post { done(Triple(a, h, t)) }
                    }
                } catch (e: Exception) { handler.post { done(null) } }
            }
        })
    }

    /** 한 사이클: 매치업 확인 → 스코어 조회 → 알림 갱신 → 다음 폴링 예약 */
    private fun pollOnce(next: (Long) -> Unit) {
        if (System.currentTimeMillis() - lastOkMs > STALE_LIMIT_MS) {
            stopSelfRemoving()   // 도즈 등으로 오래 얼어 있었으면 stale 표시 방지
            return
        }
        resolveMatchup { m ->
            if (m == null) { stopSelfRemoving(); return@resolveMatchup }
            fetchScores(m.first, m.second, m.third, next)
        }
    }

    private fun fetchScores(away: String, home: String, startTime: String, next: (Long) -> Unit) {
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
                    lastOkMs = System.currentTimeMillis()
                    handler.post { render(game, startTime, next) }
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
                notifyKeeping(nm, buildLiveNotification(game))
                next(POLL_LIVE_MS)
            }
            "2" -> finishWith(buildFinalNotification(game))
            "3" -> finishWith(buildTextNotification(
                "${game.getString("away")} vs ${game.getString("home")} 경기취소",
                game.optString("inning", "우천취소"), ongoing = false))
            else -> {
                notifyKeeping(nm, buildTextNotification(
                    "오늘 ${startTime.ifEmpty { "저녁" }} 경기 시작 예정",
                    "시작하면 실시간 스코어로 바뀝니다", ongoing = true))
                next(POLL_PRE_MS)
            }
        }
    }

    private fun notifyKeeping(nm: NotificationManager, n: Notification) {
        lastNotification = n
        nm.notify(NOTI_ID, n)
    }

    // ── 알림 빌더들 ──

    private fun buildLiveNotification(s: JSONObject): Notification {
        val away = s.getString("away"); val home = s.getString("home")
        val title = "$away ${s.optString("away_score", "-")} : " +
                    "${s.optString("home_score", "-")} $home"
        val inning = s.optString("inning", "")
        val isBot = inning.contains("말")
        val inningShort = Regex("(\\d+)").find(inning)?.let {
            (if (isBot) "▼" else "▲") + it.groupValues[1] + "회"
        } ?: inning
        val outs = s.optInt("outs", 0)
        val bases = s.optJSONArray("bases")
        val baseNames = mutableListOf<String>()
        if (bases != null) {
            if (bases.optBoolean(0)) baseNames.add("1루")
            if (bases.optBoolean(1)) baseNames.add("2루")
            if (bases.optBoolean(2)) baseNames.add("3루")
        }
        val situText = "${outs}사 " +
            if (baseNames.isEmpty()) "주자 없음" else "주자 ${baseNames.joinToString("·")}"

        // 펼친 뷰: 중앙 스코어 + [이닝|주자|B·S·O] 바 + 투/타
        val big = RemoteViews(packageName, R.layout.notification_live)
        big.setTextViewText(R.id.tv_n_score, title)
        if (bases != null && s.optInt("balls", -1) >= 0) {
            big.setImageViewBitmap(R.id.iv_n_bar, SituationDrawer.makeNotificationBar(
                inning,
                booleanArrayOf(bases.optBoolean(0), bases.optBoolean(1), bases.optBoolean(2)),
                s.optInt("balls", 0), s.optInt("strikes", 0), s.optInt("outs", 0)))
            big.setViewVisibility(R.id.iv_n_bar, android.view.View.VISIBLE)
        } else {
            big.setViewVisibility(R.id.iv_n_bar, android.view.View.GONE)
        }
        big.setTextViewText(R.id.tv_n_players, currentPlayersText())

        // 접힌 뷰: 점수 + 이닝 + 아웃/주자 요약 한 줄
        val small = RemoteViews(packageName, R.layout.notification_live_small)
        small.setTextViewText(R.id.tv_ns_score, title)
        small.setTextViewText(R.id.tv_ns_inning, inningShort)
        small.setTextColor(R.id.tv_ns_inning,
            Color.parseColor(if (isBot) "#1B3A6B" else "#FF6B6B"))
        small.setTextViewText(R.id.tv_ns_situ, situText)

        return baseBuilder(ongoing = true)
            .setContentTitle(title)
            .setContentText("$inning · $situText")
            .setCustomContentView(small)
            .setCustomBigContentView(big)
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
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (ongoing) {
            // 안드14+에선 사용자가 FGS 알림도 지울 수 있음 — 지워지면 즉시 재표시.
            // 최종/취소(비고정) 알림엔 붙이지 않아 재소환 루프를 막는다.
            b.setDeleteIntent(PendingIntent.getBroadcast(
                this, 78,
                Intent(this, LiveNotiAlarmReceiver::class.java).setAction(ACTION_REPOST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return b
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
        lastNotification = null
        // DETACH: 포그라운드 해제하되 알림은 남긴다 → 최종 스코어가 지워지지 않고 표시됨
        stopForeground(STOP_FOREGROUND_DETACH)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_ID, finalNoti)
        stopSelf()
    }

    private fun stopSelfRemoving() {
        polling?.let { handler.removeCallbacks(it) }
        lastNotification = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("kbo_live_score")  // 구 LOW 채널 제거
            // DEFAULT 중요도: 목록 상단 배치 → 최상단 알림은 대체로 자동 확장됨.
            // 알림 자체는 setSilent라 소리/진동 없음.
            val ch = NotificationChannel(
                CHANNEL_ID, "라이브 스코어 고정 알림", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "경기 중 점수·이닝·주자 상황을 알림창에 고정 표시"
            ch.setShowBadge(false)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val BASE = "https://web-production-6aae76.up.railway.app"
        private const val CHANNEL_ID = "kbo_live_score_v2"
        private const val NOTI_ID = 1001
        private const val POLL_LIVE_MS = 30_000L
        private const val POLL_PRE_MS = 180_000L
        private const val STALE_LIMIT_MS = 30 * 60_000L
        const val ACTION_REPOST = "com.example.kbowidget.LIVE_NOTI_REPOST"

        @Volatile private var running = false

        /**
         * 토글 on이면 서비스 기동. 호출 컨텍스트: MainActivity(포그라운드),
         * 경기 시작 정확 알람, 위젯 알람 체인(정확 알람) — 모두 FGS 시작 허용 경로.
         * 오늘 경기가 이미 종료/취소로 확정(위젯·앱 캐시 기준)이면 띄우지 않고,
         * '오늘 경기 없음' 판단은 서비스가 스스로 확인 후 종료한다.
         */
        fun startIfEnabled(context: Context) {
            val prefs = KboCommon.prefs(context)
            if (!prefs.getBoolean("live_noti", false)) return
            if (running) return
            val today = KboCommon.getGameDate()
            val ended = listOf("reg", "app").any { pfx ->
                prefs.getString("${pfx}_date", "") == today &&
                (prefs.getString("${pfx}_status", "") in listOf("2", "3"))
            }
            if (ended) return
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
