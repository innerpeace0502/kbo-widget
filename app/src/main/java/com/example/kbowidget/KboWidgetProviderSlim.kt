package com.example.kbowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class KboWidgetProviderSlim : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // TickService는 메인 앱 실행 중에만 동작. 위젯 갱신은 알람 체인으로
        try { context.startService(Intent(context, TickService::class.java)) } catch (_: Exception) {}
        KboWidgetProvider.scheduleFetchAlarm(context)
        scheduleSlimFetchAlarm(context)
        for (id in appWidgetIds) {
            fetchGameData(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(
            ComponentName(context, KboWidgetProviderSlim::class.java)
        )
        when (intent.action) {
            KboWidgetProvider.ACTION_FETCH,
            Intent.ACTION_BOOT_COMPLETED -> {
                // ❌ 백그라운드 startService 제거 (Android 8+ ForegroundServiceStartNotAllowedException 회피)
                scheduleSlimFetchAlarm(context)
                ids.forEach { fetchGameData(context, awm, it) }
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 슬림 위젯 자체 알람 취소
        cancelSlimAlarm(context)
        val awm = AppWidgetManager.getInstance(context)
        val regularIds = awm.getAppWidgetIds(
            ComponentName(context, KboWidgetProvider::class.java)
        )
        if (regularIds.isEmpty()) {
            KboWidgetProvider.cancelAlarms(context)
            context.stopService(Intent(context, TickService::class.java))
        }
    }

    companion object {
        // ✅ 슬림 위젯 전용 60초 갱신 알람
        fun scheduleSlimFetchAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, KboWidgetProviderSlim::class.java).apply {
                    action = KboWidgetProvider.ACTION_FETCH
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                pi
            )
        }

        fun cancelSlimAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, KboWidgetProviderSlim::class.java).apply {
                    action = KboWidgetProvider.ACTION_FETCH
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    // ✅ 투명도: "bg_alpha"(0~100%) 읽어서 0~255로 변환 후 적용
    private fun applyBackground(context: Context, v: RemoteViews) {
        val prefs   = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val alpha   = prefs.getInt("bg_alpha", 94) * 255 / 100  // % → 0~255
        val bgColor = (alpha shl 24) or 0x1A1A2E
        v.setInt(R.id.widget_slim_root, "setBackgroundColor", bgColor)
    }

    private fun fetchGameData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        // ✅ 04:00 기준 게임 날짜 변경 시 모든 캐시 일괄 클리어
        KboCommon.clearCacheIfDateChanged(context)

        val prefs = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val team  = prefs.getString("team", "전체") ?: "전체"
        val iptv  = prefs.getString("iptv", "") ?: ""

        val teamParam = if (team == "전체") "" else "?team=$team"
        val url = "https://web-production-6aae76.up.railway.app/api/schedule/today$teamParam"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        client.newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    println("[슬림위젯] API 호출 실패: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body  = response.body?.string() ?: return
                    val json  = JSONObject(body)
                    val games = json.getJSONArray("경기목록")
                    val v     = RemoteViews(context.packageName, R.layout.widget_layout_slim)

                    applyBackground(context, v)

                    val launchIntent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    v.setOnClickPendingIntent(R.id.widget_slim_root, pendingIntent)

                    if (games.length() == 0) {
                        // 경기 없음 — 로고 자리에 텍스트 대신 기본 원형 표시
                        v.setImageViewBitmap(R.id.iv_slim_logo_away,
                            KboWidgetProvider.makeCircleBitmap("?", 0xFF444444.toInt(), 90))
                        v.setImageViewBitmap(R.id.iv_slim_logo_home,
                            KboWidgetProvider.makeCircleBitmap("?", 0xFF444444.toInt(), 90))
                        v.setViewVisibility(R.id.tv_slim_main,        android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.ll_slim_live_row,    android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_ended_row,   android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_channel_row, android.view.View.GONE)
                        v.setViewVisibility(R.id.tv_slim_score_away,  android.view.View.GONE)
                        v.setViewVisibility(R.id.tv_slim_score_home,  android.view.View.GONE)
                        v.setTextViewText(R.id.tv_slim_main, "오늘 경기 없음")
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                        return
                    }

                    val game         = games.getJSONObject(0)
                    val away         = game.getString("away")
                    val home         = game.getString("home")
                    val gameTime     = game.getString("time")
                    val stadium      = game.optString("stadium", "")
                    val broadcast    = game.optString("broadcast", "")

                    val baseUrl  = "https://web-production-6aae76.up.railway.app"
                    val awayLogo = "$baseUrl/logos/$away"
                    val homeLogo = "$baseUrl/logos/$home"

                    // ✅ SharedPreferences에서 저장된 상태 확인
                    val savedStatus    = prefs.getString("slim_status", "") ?: ""
                    val savedAwayScore = prefs.getString("slim_away_score", "") ?: ""
                    val savedHomeScore = prefs.getString("slim_home_score", "") ?: ""
                    val savedStadium   = prefs.getString("slim_stadium", "") ?: ""
                    val savedDate      = prefs.getString("slim_date", "") ?: ""
                    val today          = com.example.kbowidget.KboWidgetProvider.run {
                        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                            .format(java.util.Calendar.getInstance().time)
                    }

                    // reg_* 또는 app_*에 오늘 종료 스코어가 있으면 slim_* 스테일 값보다 우선 사용
                    // (팀명 매칭 없이 날짜만 확인 — 하루에 한 팀 한 경기이므로 충분)
                    fun findEndedScore(): Pair<String, String>? {
                        for (pfx in listOf("reg", "app")) {
                            val st  = prefs.getString("${pfx}_status", "") ?: ""
                            val as_ = prefs.getString("${pfx}_away_score", "") ?: ""
                            val hs_ = prefs.getString("${pfx}_home_score", "") ?: ""
                            val dt  = prefs.getString("${pfx}_date", "") ?: ""
                            if (st == "2" && as_.isNotEmpty() && dt == today) return as_ to hs_
                        }
                        return null
                    }
                    val endedScore = findEndedScore()

                    // slim_status가 "1"(진행중 stale)인데 app_*에 종료 스코어가 있으면 종료 상태로 표시
                    val effectiveStatus    = if (endedScore != null && savedStatus != "2") "2" else savedStatus
                    val effectiveAwayScore = endedScore?.first ?: savedAwayScore
                    val effectiveHomeScore = endedScore?.second ?: savedHomeScore

                    if ((effectiveStatus == "2" || effectiveStatus == "1") &&
                        effectiveAwayScore.isNotEmpty() && (savedDate == today || endedScore != null)) {
                        // 오늘 날짜의 경기 중/종료 상태가 있으면 즉시 표시
                        val awayInt = effectiveAwayScore.toIntOrNull() ?: 0
                        val homeInt = effectiveHomeScore.toIntOrNull() ?: 0
                        if (effectiveStatus == "2") {
                            v.setViewVisibility(R.id.tv_slim_main,         android.view.View.GONE)
                            v.setViewVisibility(R.id.ll_slim_live_row,     android.view.View.GONE)
                            v.setViewVisibility(R.id.ll_slim_ended_row,    android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.ll_slim_channel_row,  android.view.View.GONE)
                            v.setViewVisibility(R.id.tv_slim_score_away,   android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.tv_slim_score_home,   android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.ll_slim_pitcher_away, android.view.View.GONE)
                            v.setViewVisibility(R.id.ll_slim_pitcher_home, android.view.View.GONE)
                            v.setTextViewText(R.id.tv_slim_ended_main,  savedStadium)
                            v.setTextViewText(R.id.tv_slim_score_away,  effectiveAwayScore)
                            v.setTextViewText(R.id.tv_slim_score_home,  effectiveHomeScore)
                            v.setTextColor(R.id.tv_slim_score_away,
                                if (awayInt > homeInt) 0xFFFFD700.toInt() else 0xFF555555.toInt())
                            v.setTextColor(R.id.tv_slim_score_home,
                                if (homeInt > awayInt) 0xFFFFD700.toInt() else 0xFF555555.toInt())
                        } else { // effectiveStatus == "1"
                            v.setViewVisibility(R.id.tv_slim_main,         android.view.View.GONE)
                            v.setViewVisibility(R.id.ll_slim_live_row,     android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.ll_slim_ended_row,    android.view.View.GONE)
                            v.setViewVisibility(R.id.ll_slim_channel_row,  android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.tv_slim_score_away,   android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.tv_slim_score_home,   android.view.View.VISIBLE)
                            v.setViewVisibility(R.id.ll_slim_pitcher_away, android.view.View.GONE)
                            v.setViewVisibility(R.id.ll_slim_pitcher_home, android.view.View.GONE)
                            v.setTextViewText(R.id.tv_slim_score_away, effectiveAwayScore)
                            v.setTextViewText(R.id.tv_slim_score_home, effectiveHomeScore)
                            v.setTextColor(R.id.tv_slim_score_away,
                                if (awayInt >= homeInt) 0xFFFFD700.toInt() else 0xFF777777.toInt())
                            v.setTextColor(R.id.tv_slim_score_home,
                                if (homeInt >= awayInt) 0xFFFFD700.toInt() else 0xFF777777.toInt())
                        }
                    } else {
                        // 저장된 상태 없거나 날짜 다름 → 경기 전 기본 상태
                        v.setTextViewText(R.id.tv_slim_main, "$gameTime · $stadium")
                        v.setViewVisibility(R.id.tv_slim_main,           android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.ll_slim_live_row,       android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_ended_row,      android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_channel_row,    android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.tv_slim_score_away,     android.view.View.GONE)
                        v.setViewVisibility(R.id.tv_slim_score_home,     android.view.View.GONE)
                        // 투수 뷰를 VISIBLE로 유지 → 로고 위치가 경기 중과 동일하게 고정
                        // (fetchPitcherInfo가 이름을 채워 넣음, 빈 상태에서도 레이아웃 공간 유지)
                        v.setTextViewText(R.id.tv_slim_pitcher_away, "")
                        v.setTextViewText(R.id.tv_slim_pitcher_home, "")
                        v.setViewVisibility(R.id.ll_slim_pitcher_away,   android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.ll_slim_pitcher_home,   android.view.View.VISIBLE)
                    }

                    val awayColor  = KboWidgetProvider.teamColors[away] ?: 0xFF444444.toInt()
                    val homeColor  = KboWidgetProvider.teamColors[home] ?: 0xFF444444.toInt()
                    val cachedAway = KboWidgetProvider.logoCache[away]
                    val cachedHome = KboWidgetProvider.logoCache[home]

                    if (cachedAway != null) {
                        v.setImageViewBitmap(R.id.iv_slim_logo_away, cachedAway)
                    } else {
                        v.setImageViewBitmap(R.id.iv_slim_logo_away,
                            KboWidgetProvider.makeCircleBitmap(away, awayColor, 90))
                    }
                    if (cachedHome != null) {
                        v.setImageViewBitmap(R.id.iv_slim_logo_home, cachedHome)
                    } else {
                        v.setImageViewBitmap(R.id.iv_slim_logo_home,
                            KboWidgetProvider.makeCircleBitmap(home, homeColor, 90))
                    }

                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)

                    if (cachedAway == null) {
                        KboWidgetProvider.loadLogoBitmapCached(context, awayLogo, away) { bmp ->
                            bmp?.let {
                                val v2 = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                                v2.setImageViewBitmap(R.id.iv_slim_logo_away, it)
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v2)
                            }
                        }
                    }
                    if (cachedHome == null) {
                        KboWidgetProvider.loadLogoBitmapCached(context, homeLogo, home) { bmp ->
                            bmp?.let {
                                val v2 = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                                v2.setImageViewBitmap(R.id.iv_slim_logo_home, it)
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v2)
                            }
                        }
                    }

                    if (iptv.isNotEmpty()) {
                        if (broadcast.isNotEmpty()) {
                            fetchChannel(context, appWidgetManager, appWidgetId, iptv, broadcast)
                        } else {
                            fetchBothChannels(context, appWidgetManager, appWidgetId, iptv)
                        }
                    }

                    // 선발투수 별도 API 호출 (schedule API와 분리)
                    fetchPitcherInfo(context, appWidgetManager, appWidgetId, away, home, team)
                    fetchLiveScore(context, appWidgetManager, appWidgetId, away, home, stadium)
                }
            })
    }

    private fun fetchPitcherInfo(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        away: String,
        home: String,
        team: String
    ) {
        val teamParam = if (team == "전체") "" else "?team=$team"
        val url = "https://web-production-6aae76.up.railway.app/api/pitcher/today$teamParam"

        OkHttpClient().newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    // 경기가 이미 시작/종료/취소됐으면 선발투수를 표시하지 않는다.
                    // (fetchLiveScore가 GONE 처리해도 이 콜백이 더 늦게 도착하면 선발을 다시
                    //  켜버리는 경쟁 조건 방지 — 라이브 시작 직후 선발과 점수가 함께 뜨던 버그)
                    val gPrefs = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
                    val gToday = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                        .format(java.util.Calendar.getInstance().time)
                    val gameStarted = listOf("slim", "reg", "app").any { pfx ->
                        val st = gPrefs.getString("${pfx}_status", "") ?: ""
                        val dt = gPrefs.getString("${pfx}_date", "") ?: ""
                        dt == gToday && (st == "1" || st == "2" || st == "3")
                    }
                    if (gameStarted) return

                    val body     = response.body?.string() ?: return
                    val json     = JSONObject(body)
                    val pitchers = json.getJSONArray("pitchers")

                    for (i in 0 until pitchers.length()) {
                        val p = pitchers.getJSONObject(i)
                        if (p.getString("away") != away || p.getString("home") != home) continue

                        val ap = p.optString("away_pitcher", "")
                        val hp = p.optString("home_pitcher", "")

                        val v = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                        var updated = false
                        if (ap.isNotEmpty()) {
                            v.setTextViewText(R.id.tv_slim_pitcher_away, ap)
                            v.setViewVisibility(R.id.ll_slim_pitcher_away, android.view.View.VISIBLE)
                            updated = true
                        }
                        if (hp.isNotEmpty()) {
                            v.setTextViewText(R.id.tv_slim_pitcher_home, hp)
                            v.setViewVisibility(R.id.ll_slim_pitcher_home, android.view.View.VISIBLE)
                            updated = true
                        }
                        // 이름이 없을 때는 visibility를 건드리지 않음:
                        // - 경기 전: fetchGameData가 VISIBLE로 초기화한 상태 유지
                        // - 경기 중/종료: fetchLiveScore가 설정한 GONE 유지
                        if (updated) appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                        break
                    }
                }
            })
    }

    private fun fetchLiveScore(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        away: String,
        home: String,
        stadium: String
    ) {
        // ✅ 전체 스코어 조회 (팀 필터 제거 - 웹앱과 동일)
        val url = "https://web-production-6aae76.up.railway.app/api/scores"

        OkHttpClient().newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    println("[슬림스코어] API 호출 실패: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body   = response.body?.string() ?: return
                    val json   = JSONObject(body)
                    val scores = json.getJSONArray("scores")
                    val prefs  = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)

                    var found = false
                    for (i in 0 until scores.length()) {
                        val score = scores.getJSONObject(i)
                        val sAway = score.getString("away")
                        val sHome = score.getString("home")

                        // ✅ 홈/어웨이 반전도 체크 (웹앱과 동일)
                        if (!((sAway == away && sHome == home) ||
                                    (sAway == home && sHome == away))) continue

                        found = true
                        val status    = score.getString("status")
                        val awayScore = score.getString("away_score")
                        val homeScore = score.getString("home_score")
                        val inning    = score.getString("inning")
                        val awayInt   = awayScore.toIntOrNull() ?: 0
                        val homeInt   = homeScore.toIntOrNull() ?: 0

                        // ✅ SharedPreferences에 날짜 포함 저장
                        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                            .format(java.util.Calendar.getInstance().time)
                        prefs.edit()
                            .putString("slim_status",     status)
                            .putString("slim_away_score", awayScore)
                            .putString("slim_home_score", homeScore)
                            .putString("slim_stadium",    stadium)
                            .putString("slim_date",       todayStr)
                            .apply()

                        val v = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                        applyBackground(context, v)

                        when (status) {
                            "1" -> { // 경기 중
                                v.setViewVisibility(R.id.tv_slim_main,           android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_live_row,       android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.ll_slim_ended_row,      android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_channel_row,    android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.tv_slim_score_away,     android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.tv_slim_score_home,     android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.ll_slim_pitcher_away,   android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_pitcher_home,   android.view.View.GONE)
                                v.setTextViewText(R.id.tv_slim_inning,     inning)
                                v.setTextViewText(R.id.tv_slim_score_away, awayScore)
                                v.setTextViewText(R.id.tv_slim_score_home, homeScore)
                                v.setTextColor(R.id.tv_slim_score_away,
                                    if (awayInt >= homeInt) 0xFFFFD700.toInt() else 0xFF777777.toInt())
                                v.setTextColor(R.id.tv_slim_score_home,
                                    if (homeInt >= awayInt) 0xFFFFD700.toInt() else 0xFF777777.toInt())
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                            }
                            "2" -> { // 경기 종료
                                v.setViewVisibility(R.id.tv_slim_main,           android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_live_row,       android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_ended_row,      android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.ll_slim_channel_row,    android.view.View.GONE)
                                v.setViewVisibility(R.id.tv_slim_score_away,     android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.tv_slim_score_home,     android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.ll_slim_pitcher_away,   android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_pitcher_home,   android.view.View.GONE)
                                v.setTextViewText(R.id.tv_slim_ended_main,  stadium)
                                v.setTextViewText(R.id.tv_slim_score_away,  awayScore)
                                v.setTextViewText(R.id.tv_slim_score_home,  homeScore)
                                v.setTextColor(R.id.tv_slim_score_away,
                                    if (awayInt > homeInt) 0xFFFFD700.toInt() else 0xFF555555.toInt())
                                v.setTextColor(R.id.tv_slim_score_home,
                                    if (homeInt > awayInt) 0xFFFFD700.toInt() else 0xFF555555.toInt())
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                            }
                            "3" -> { // 우천취소 — 점수 숨기고 메인에 "경기취소"
                                v.setViewVisibility(R.id.tv_slim_main,        android.view.View.VISIBLE)
                                v.setViewVisibility(R.id.ll_slim_live_row,    android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_ended_row,   android.view.View.GONE)
                                v.setViewVisibility(R.id.ll_slim_channel_row, android.view.View.GONE)
                                v.setViewVisibility(R.id.tv_slim_score_away,  android.view.View.GONE)
                                v.setViewVisibility(R.id.tv_slim_score_home,  android.view.View.GONE)
                                v.setTextViewText(R.id.tv_slim_main, "경기취소")
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                            }
                        }
                        break
                    }

                    // ✅ 경기를 찾지 못한 경우 → reg_*/app_* 확정 종료 스코어로 복원
                    // reg_*/app_*를 못 찾으면 아무것도 덮어쓰지 않음 (fetchGameData 사전 표시 유지)
                    if (!found) {
                        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                            .format(java.util.Calendar.getInstance().time)

                        var fallbackAway = ""; var fallbackHome = ""
                        for (pfx in listOf("reg", "app")) {
                            val st  = prefs.getString("${pfx}_status", "") ?: ""
                            val as_ = prefs.getString("${pfx}_away_score", "") ?: ""
                            val hs_ = prefs.getString("${pfx}_home_score", "") ?: ""
                            val dt  = prefs.getString("${pfx}_date", "") ?: ""
                            if (st == "2" && as_.isNotEmpty() && dt == todayStr) {
                                fallbackAway = as_; fallbackHome = hs_; break
                            }
                        }

                        // 확정 종료 스코어가 없으면 덮어쓰지 않음 (pre-check 표시 유지)
                        if (fallbackAway.isEmpty()) return

                        val slimStadium = prefs.getString("slim_stadium", "") ?: ""
                        prefs.edit()
                            .putString("slim_status",     "2")
                            .putString("slim_away_score", fallbackAway)
                            .putString("slim_home_score", fallbackHome)
                            .putString("slim_date",       todayStr)
                            .apply()

                        val awayInt = fallbackAway.toIntOrNull() ?: 0
                        val homeInt = fallbackHome.toIntOrNull() ?: 0
                        val v = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                        applyBackground(context, v)
                        v.setViewVisibility(R.id.tv_slim_main,           android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_live_row,       android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_ended_row,      android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.ll_slim_channel_row,    android.view.View.GONE)
                        v.setViewVisibility(R.id.tv_slim_score_away,     android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.tv_slim_score_home,     android.view.View.VISIBLE)
                        v.setViewVisibility(R.id.ll_slim_pitcher_away,   android.view.View.GONE)
                        v.setViewVisibility(R.id.ll_slim_pitcher_home,   android.view.View.GONE)
                        v.setTextViewText(R.id.tv_slim_ended_main,  slimStadium)
                        v.setTextViewText(R.id.tv_slim_score_away,  fallbackAway)
                        v.setTextViewText(R.id.tv_slim_score_home,  fallbackHome)
                        v.setTextColor(R.id.tv_slim_score_away,
                            if (awayInt > homeInt) 0xFFFFD700.toInt() else 0xFF555555.toInt())
                        v.setTextColor(R.id.tv_slim_score_home,
                            if (homeInt > awayInt) 0xFFFFD700.toInt() else 0xFF555555.toInt())
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                    }
                }
            })
    }

    private fun fetchChannel(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        iptv: String,
        broadcaster: String
    ) {
        val name = when (broadcaster) {
            "spotv"        -> "SPOTV"
            "spotv2"       -> "SPOTV2"
            "kbs_n_sports" -> "KBS N스포츠"
            "mbc_sports"   -> "MBC스포츠+"
            "sbs_sports"   -> "SBS스포츠"
            "kbs2"         -> "KBS2"
            "mbc"          -> "MBC"
            "sbs"          -> "SBS"
            "tving"        -> "TVING"
            else           -> broadcaster.uppercase()
        }
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://web-production-6aae76.up.railway.app/api/channel?iptv=$iptv&broadcaster=$broadcaster")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val ch = JSONObject(response.body?.string() ?: "{}").optString("채널번호", "")
                val v  = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                v.setTextViewText(R.id.tv_slim_channel_name, name)
                if (ch.isNotEmpty() && broadcaster != "tving") {
                    v.setTextViewText(R.id.tv_slim_channel_num, ch)
                    v.setViewVisibility(R.id.tv_slim_channel_num, android.view.View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.tv_slim_channel_num, android.view.View.GONE)
                }
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
            }
        })
    }

    private fun fetchBothChannels(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        iptv: String
    ) {
        val client = OkHttpClient()
        var ch1 = ""; var ch2 = ""; var count = 0

        fun done() {
            count++
            if (count == 2) {
                val v = RemoteViews(context.packageName, R.layout.widget_layout_slim)
                v.setTextViewText(R.id.tv_slim_channel_name, "SPOTV / SPOTV2")
                v.setTextViewText(R.id.tv_slim_channel_num, "$ch1 / $ch2")
                v.setViewVisibility(R.id.tv_slim_channel_num, android.view.View.VISIBLE)
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
            }
        }

        client.newCall(Request.Builder()
            .url("https://web-production-6aae76.up.railway.app/api/channel?iptv=$iptv&broadcaster=spotv")
            .build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { ch1 = "?"; done() }
            override fun onResponse(call: Call, response: Response) {
                ch1 = JSONObject(response.body?.string() ?: "{}").optString("채널번호", "?")
                done()
            }
        })

        client.newCall(Request.Builder()
            .url("https://web-production-6aae76.up.railway.app/api/channel?iptv=$iptv&broadcaster=spotv2")
            .build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { ch2 = "?"; done() }
            override fun onResponse(call: Call, response: Response) {
                ch2 = JSONObject(response.body?.string() ?: "{}").optString("채널번호", "?")
                done()
            }
        })
    }
}