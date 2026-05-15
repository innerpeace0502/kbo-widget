package com.example.kbowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.SystemClock
import android.widget.RemoteViews
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class KboWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_FETCH = "com.example.kbowidget.ACTION_FETCH"

        val logoCache = mutableMapOf<String, Bitmap>()

        val teamColors = mapOf(
            "LG"  to 0xFFC30452.toInt(),
            "KT"  to 0xFFE31E26.toInt(),
            "SSG" to 0xFFCE0E2D.toInt(),
            "NC"  to 0xFF071D49.toInt(),
            "두산" to 0xFF131230.toInt(),
            "KIA" to 0xFFEA0029.toInt(),
            "롯데" to 0xFF041E42.toInt(),
            "삼성" to 0xFF0055A8.toInt(),
            "한화" to 0xFFFF6600.toInt(),
            "키움" to 0xFF820024.toInt()
        )

        fun makeCircleBitmap(text: String, bgColor: Int, sizePx: Int = 120): Bitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = bgColor
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
            paint.color     = Color.WHITE
            paint.textSize  = sizePx * 0.32f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface  = Typeface.DEFAULT_BOLD
            val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, sizePx / 2f, y, paint)
            return bitmap
        }

        /**
         * 팀 로고 로드 — 메모리 캐시 → 디스크 캐시 → 네트워크 순으로 폴백.
         * 디스크 캐시는 `<cacheDir>/kbo_logos/<team>.png`에 저장.
         */
        fun loadLogoBitmap(url: String, team: String, onLoaded: (Bitmap?) -> Unit) {
            logoCache[team]?.let { onLoaded(it); return }
            // 디스크 캐시는 context가 없어 호출자가 별도 제공해야 함. 메모리 캐시 미스 시 네트워크.
            OkHttpClient().newCall(Request.Builder().url(url).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { onLoaded(null) }
                    override fun onResponse(call: Call, response: Response) {
                        val bytes = response.body?.bytes() ?: run { onLoaded(null); return }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        bmp?.let { logoCache[team] = it }
                        onLoaded(bmp)
                    }
                })
        }

        /**
         * Context를 사용해 디스크 캐시까지 활용하는 로고 로드.
         * 1) 메모리 캐시 hit → 즉시 반환
         * 2) 디스크 캐시 hit → 메모리 캐시에 저장 + 반환
         * 3) 네트워크 → 디스크 + 메모리 캐시 저장 + 반환
         */
        fun loadLogoBitmapCached(context: Context, url: String, team: String, onLoaded: (Bitmap?) -> Unit) {
            logoCache[team]?.let { onLoaded(it); return }
            val cacheDir = java.io.File(context.cacheDir, "kbo_logos").apply { mkdirs() }
            val cacheFile = java.io.File(cacheDir, "$team.png")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bmp != null) {
                    logoCache[team] = bmp
                    onLoaded(bmp)
                    return
                }
            }
            OkHttpClient().newCall(Request.Builder().url(url).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { onLoaded(null) }
                    override fun onResponse(call: Call, response: Response) {
                        val bytes = response.body?.bytes() ?: run { onLoaded(null); return }
                        // 디스크 캐시 저장
                        try { cacheFile.writeBytes(bytes) } catch (_: Exception) {}
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        bmp?.let { logoCache[team] = it }
                        onLoaded(bmp)
                    }
                })
        }

        fun scheduleFetchAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, KboWidgetProvider::class.java).apply { action = ACTION_FETCH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                pi
            )
        }

        fun cancelAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, KboWidgetProvider::class.java).apply { action = ACTION_FETCH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // TickService는 메인 앱 실행 중에만 시계 갱신용으로 동작
        // 위젯 자동 갱신은 AlarmManager 체인이 담당 (Android 8+ 백그라운드 startService 제한 회피)
        tryStartTickServiceSafely(context)
        scheduleFetchAlarm(context)
        for (id in appWidgetIds) {
            fetchGameData(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(
            ComponentName(context, KboWidgetProvider::class.java)
        )
        when (intent.action) {
            ACTION_FETCH,
            Intent.ACTION_BOOT_COMPLETED -> {
                // ❌ 백그라운드에서 startService 호출 시 ForegroundServiceStartNotAllowedException 발생 가능
                // 알람 체인은 startService 없이도 작동해야 함
                ids.forEach { fetchGameData(context, awm, it) }
                scheduleFetchAlarm(context)
            }
        }
    }

    /** 메인 앱 실행 중인 경우(포그라운드)에만 안전하게 TickService 시작 */
    private fun tryStartTickServiceSafely(context: Context) {
        try {
            context.startService(Intent(context, TickService::class.java))
        } catch (_: Exception) {
            // 백그라운드 제한 - 무시
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelAlarms(context)
        context.stopService(Intent(context, TickService::class.java))
    }

    private fun fetchGameData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        // ✅ 04:00 기준 게임 날짜 변경 시 모든 캐시 일괄 클리어 (어제 종료 스코어 자동 제거)
        KboCommon.clearCacheIfDateChanged(context)

        val prefs = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val team  = prefs.getString("team", "전체") ?: "전체"
        val iptv  = prefs.getString("iptv", "") ?: ""
        // ✅ 키 "bg_alpha"로 통일, 0~100% 값을 0~255로 변환
        val bgAlpha = prefs.getInt("bg_alpha", 94) * 255 / 100

        val teamParam = if (team == "전체") "" else "?team=$team"
        val url = "https://web-production-6aae76.up.railway.app/api/schedule/today$teamParam"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        client.newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    println("[위젯] API 호출 실패: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body  = response.body?.string() ?: return
                    val json  = JSONObject(body)
                    val games = json.getJSONArray("경기목록")
                    val v     = RemoteViews(context.packageName, R.layout.widget_layout)

                    // ✅ 투명도 배경 적용 (이미 0~255로 변환된 값 사용)
                    val bgColor = (bgAlpha shl 24) or 0x1A1A2E
                    v.setInt(R.id.widget_root, "setBackgroundColor", bgColor)

                    val launchIntent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    v.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                    if (games.length() == 0) {
                        v.setTextViewText(R.id.tv_away,      "오늘")
                        v.setTextViewText(R.id.tv_home,      "경기없음")
                        v.setTextViewText(R.id.tv_game_time, "")
                        v.setTextViewText(R.id.tv_stadium,   "")
                        v.setTextViewText(R.id.tv_channel,   "")
                        v.setTextViewText(R.id.tv_vs,        "")
                        v.setViewVisibility(R.id.tv_score_away,  android.view.View.GONE)
                        v.setViewVisibility(R.id.tv_score_home,  android.view.View.GONE)
                        v.setViewVisibility(R.id.tv_live_inning, android.view.View.GONE)
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                        return
                    }

                    val game      = games.getJSONObject(0)
                    val away      = game.getString("away")
                    val home      = game.getString("home")
                    val gameTime  = game.getString("time")
                    val stadium   = game.optString("stadium", "")
                    val broadcast = game.optString("broadcast", "")

                    val baseUrl  = "https://web-production-6aae76.up.railway.app"
                    val awayLogo = "$baseUrl/logos/$away"
                    val homeLogo = "$baseUrl/logos/$home"

                    v.setTextViewText(R.id.tv_away,      away)
                    v.setTextViewText(R.id.tv_home,      home)
                    v.setTextViewText(R.id.tv_game_time, "$gameTime 시작")
                    v.setTextViewText(R.id.tv_stadium,   stadium)

                    val awayColor  = teamColors[away] ?: 0xFF444444.toInt()
                    val homeColor  = teamColors[home] ?: 0xFF444444.toInt()
                    val cachedAway = logoCache[away]
                    val cachedHome = logoCache[home]

                    if (cachedAway != null) {
                        v.setImageViewBitmap(R.id.iv_logo_away, cachedAway)
                    } else {
                        v.setImageViewBitmap(R.id.iv_logo_away, makeCircleBitmap(away, awayColor))
                    }
                    if (cachedHome != null) {
                        v.setImageViewBitmap(R.id.iv_logo_home, cachedHome)
                    } else {
                        v.setImageViewBitmap(R.id.iv_logo_home, makeCircleBitmap(home, homeColor))
                    }

                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)

                    // 캐시된 스코어 즉시 표시 (reg_* 우선, 없으면 app_* 폴백)
                    run {
                        val today2 = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                            .format(java.util.Calendar.getInstance().time)
                        val regSt = prefs.getString("reg_status","") ?: ""
                        val regAs = prefs.getString("reg_away_score","") ?: ""
                        val regHs = prefs.getString("reg_home_score","") ?: ""
                        val regDt = prefs.getString("reg_date","") ?: ""
                        val regAw = prefs.getString("reg_away","") ?: ""
                        val regHo = prefs.getString("reg_home","") ?: ""
                        val appSt = prefs.getString("app_status","") ?: ""
                        val appAs = prefs.getString("app_away_score","") ?: ""
                        val appHs = prefs.getString("app_home_score","") ?: ""
                        val appDt = prefs.getString("app_date","") ?: ""
                        val appAw = prefs.getString("app_away","") ?: ""
                        val appHo = prefs.getString("app_home","") ?: ""
                        val useReg = (regSt=="1"||regSt=="2") && regAs.isNotEmpty() && regDt==today2
                            && ((regAw==away&&regHo==home)||(regAw==home&&regHo==away))
                        val useApp = !useReg && (appSt=="1"||appSt=="2") && appAs.isNotEmpty() && appDt==today2
                            && ((appAw==away&&appHo==home)||(appAw==home&&appHo==away))
                        if (useReg || useApp) {
                            val aScore = if (useReg) regAs else appAs
                            val hScore = if (useReg) regHs else appHs
                            val status = if (useReg) regSt else appSt
                            val vc = RemoteViews(context.packageName, R.layout.widget_layout)
                            vc.setViewVisibility(R.id.tv_score_away,  android.view.View.VISIBLE)
                            vc.setViewVisibility(R.id.tv_score_home,  android.view.View.VISIBLE)
                            vc.setViewVisibility(R.id.tv_live_inning, android.view.View.VISIBLE)
                            vc.setTextViewText(R.id.tv_score_away, aScore)
                            vc.setTextViewText(R.id.tv_score_home, hScore)
                            if (status == "2") {
                                vc.setTextViewText(R.id.tv_live_inning, "최종")
                                vc.setTextViewText(R.id.tv_game_time,   "경기종료")
                            } else {
                                val inning = if (useReg) (prefs.getString("reg_inning","") ?: "") else (prefs.getString("app_inning","") ?: "")
                                vc.setTextViewText(R.id.tv_live_inning, inning)
                            }
                            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, vc)
                        }
                    }

                    if (cachedAway == null) {
                        loadLogoBitmapCached(context, awayLogo, away) { bmp ->
                            bmp?.let {
                                val v2 = RemoteViews(context.packageName, R.layout.widget_layout)
                                v2.setImageViewBitmap(R.id.iv_logo_away, it)
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v2)
                            }
                        }
                    }
                    if (cachedHome == null) {
                        loadLogoBitmapCached(context, homeLogo, home) { bmp ->
                            bmp?.let {
                                val v2 = RemoteViews(context.packageName, R.layout.widget_layout)
                                v2.setImageViewBitmap(R.id.iv_logo_home, it)
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

                    fetchLiveScore(context, appWidgetManager, appWidgetId, away, home)
                }
            })
    }

    private fun fetchLiveScore(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        away: String,
        home: String
    ) {
        val url   = "https://web-production-6aae76.up.railway.app/api/scores?team=$away"
        val prefs = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)

        OkHttpClient().newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    println("[스코어] API 호출 실패: ${e.message}")
                    restoreCachedScore(context, appWidgetManager, appWidgetId, away, home, prefs)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body   = response.body?.string() ?: return
                    val json   = JSONObject(body)
                    val scores = json.getJSONArray("scores")
                    var found  = false

                    for (i in 0 until scores.length()) {
                        val score = scores.getJSONObject(i)
                        val sAway = score.getString("away")
                        val sHome = score.getString("home")

                        if (sAway == away && sHome == home) {
                            found = true
                            val status    = score.getString("status")
                            val awayScore = score.getString("away_score")
                            val homeScore = score.getString("home_score")
                            val inning    = score.getString("inning")

                            if (awayScore.isNotEmpty()) {
                                val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                                    .format(java.util.Calendar.getInstance().time)
                                prefs.edit()
                                    .putString("reg_status",     status)
                                    .putString("reg_away_score", awayScore)
                                    .putString("reg_home_score", homeScore)
                                    .putString("reg_inning",     inning)
                                    .putString("reg_date",       todayStr)
                                    .putString("reg_away",       away)
                                    .putString("reg_home",       home)
                                    .apply()
                            }

                            val v = RemoteViews(context.packageName, R.layout.widget_layout)

                            when (status) {
                                "1" -> {
                                    v.setViewVisibility(R.id.tv_score_away,  android.view.View.VISIBLE)
                                    v.setViewVisibility(R.id.tv_score_home,  android.view.View.VISIBLE)
                                    v.setViewVisibility(R.id.tv_live_inning, android.view.View.VISIBLE)
                                    v.setTextViewText(R.id.tv_score_away,  awayScore)
                                    v.setTextViewText(R.id.tv_score_home,  homeScore)
                                    v.setTextViewText(R.id.tv_live_inning, inning)
                                    v.setTextViewText(R.id.tv_game_time,   "$awayScore : $homeScore")
                                }
                                "2" -> {
                                    v.setViewVisibility(R.id.tv_score_away,  android.view.View.VISIBLE)
                                    v.setViewVisibility(R.id.tv_score_home,  android.view.View.VISIBLE)
                                    v.setViewVisibility(R.id.tv_live_inning, android.view.View.VISIBLE)
                                    v.setTextViewText(R.id.tv_score_away,  awayScore)
                                    v.setTextViewText(R.id.tv_score_home,  homeScore)
                                    v.setTextViewText(R.id.tv_live_inning, "최종")
                                    v.setTextViewText(R.id.tv_game_time,   "경기종료")
                                }
                                else -> {
                                    v.setViewVisibility(R.id.tv_score_away,  android.view.View.GONE)
                                    v.setViewVisibility(R.id.tv_score_home,  android.view.View.GONE)
                                    v.setViewVisibility(R.id.tv_live_inning, android.view.View.GONE)
                                }
                            }
                            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                            break
                        }
                    }

                    if (!found) {
                        restoreCachedScore(context, appWidgetManager, appWidgetId, away, home, prefs)
                    }
                }
            })
    }

    private fun restoreCachedScore(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        away: String,
        home: String,
        prefs: android.content.SharedPreferences
    ) {
        val savedStatus    = prefs.getString("reg_status", "") ?: ""
        val savedAwayScore = prefs.getString("reg_away_score", "") ?: ""
        val savedHomeScore = prefs.getString("reg_home_score", "") ?: ""
        val savedDate      = prefs.getString("reg_date", "") ?: ""
        val savedAway      = prefs.getString("reg_away", "") ?: ""
        val savedHome      = prefs.getString("reg_home", "") ?: ""
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
            .format(java.util.Calendar.getInstance().time)

        val regMatch = savedDate == todayStr
            && ((savedAway == away && savedHome == home) || (savedAway == home && savedHome == away))
            && (savedStatus == "1" || savedStatus == "2") && savedAwayScore.isNotEmpty()

        val (displayScore, displayHomeScore) = if (regMatch) {
            savedAwayScore to savedHomeScore
        } else {
            // app_* 폴백
            val appStatus    = prefs.getString("app_status", "") ?: ""
            val appAwayScore = prefs.getString("app_away_score", "") ?: ""
            val appHomeScore = prefs.getString("app_home_score", "") ?: ""
            val appDate      = prefs.getString("app_date", "") ?: ""
            val appAway      = prefs.getString("app_away", "") ?: ""
            val appHome      = prefs.getString("app_home", "") ?: ""
            val appMatch = appDate == todayStr
                && ((appAway == away && appHome == home) || (appAway == home && appHome == away))
                && (appStatus == "1" || appStatus == "2") && appAwayScore.isNotEmpty()
            if (!appMatch) return
            appAwayScore to appHomeScore
        }

        val v = RemoteViews(context.packageName, R.layout.widget_layout)
        v.setViewVisibility(R.id.tv_score_away,  android.view.View.VISIBLE)
        v.setViewVisibility(R.id.tv_score_home,  android.view.View.VISIBLE)
        v.setViewVisibility(R.id.tv_live_inning, android.view.View.VISIBLE)
        v.setTextViewText(R.id.tv_score_away,  displayScore)
        v.setTextViewText(R.id.tv_score_home,  displayHomeScore)
        v.setTextViewText(R.id.tv_live_inning, "최종")
        v.setTextViewText(R.id.tv_game_time,   "경기종료")
        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
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
        OkHttpClient().newCall(Request.Builder()
            .url("https://web-production-6aae76.up.railway.app/api/channel?iptv=$iptv&broadcaster=$broadcaster")
            .build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val ch = JSONObject(response.body?.string() ?: "{}").optString("채널번호", "")
                val v  = RemoteViews(context.packageName, R.layout.widget_layout)
                val chText = if (ch.isNotEmpty() && broadcaster != "tving") "$name  ${ch}ch" else name
                v.setTextViewText(R.id.tv_channel, chText)
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
                val v = RemoteViews(context.packageName, R.layout.widget_layout)
                v.setTextViewText(R.id.tv_channel, "SPOTV ${ch1}ch  /  SPOTV2 ${ch2}ch")
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