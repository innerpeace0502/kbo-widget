package com.example.kbowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class KboWidgetProvider : AppWidgetProvider() {

    companion object {
        private val handlers  = mutableMapOf<Int, Handler>()
        private val runnables = mutableMapOf<Int, Runnable>()

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

        fun loadLogoBitmap(url: String, onLoaded: (Bitmap?) -> Unit) {
            OkHttpClient().newCall(Request.Builder().url(url).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { onLoaded(null) }
                    override fun onResponse(call: Call, response: Response) {
                        val bytes = response.body?.bytes() ?: run { onLoaded(null); return }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        onLoaded(bmp)
                    }
                })
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            startClock(context, appWidgetManager, id)
            fetchGameData(context, appWidgetManager, id)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            handlers[id]?.removeCallbacks(runnables[id] ?: return)
            handlers.remove(id)
            runnables.remove(id)
        }
    }

    private fun startClock(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        handlers[appWidgetId]?.removeCallbacks(runnables[appWidgetId] ?: Runnable {})
        val handler  = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val views = RemoteViews(context.packageName, R.layout.widget_layout)
                val now   = Calendar.getInstance()
                val hm    = SimpleDateFormat("HH:mm", Locale.KOREA).format(now.time)
                val sec   = ":" + String.format("%02d", now.get(Calendar.SECOND))
                val date  = SimpleDateFormat("yyyy.MM.dd EEE", Locale.KOREA).format(now.time)
                views.setTextViewText(R.id.tv_time_hm,  hm)
                views.setTextViewText(R.id.tv_time_sec, sec)
                views.setTextViewText(R.id.tv_date,     date)
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                handler.postDelayed(this, 1000)
            }
        }
        handlers[appWidgetId]  = handler
        runnables[appWidgetId] = runnable
        handler.post(runnable)
    }

    private fun fetchGameData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val prefs = context.getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val team  = prefs.getString("team", "전체") ?: "전체"
        val iptv  = prefs.getString("iptv", "") ?: ""

        val teamParam = if (team == "전체") "" else "?team=$team"
        val url = "https://web-production-6aae76.up.railway.app/api/schedule/today$teamParam"

        OkHttpClient().newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val v = RemoteViews(context.packageName, R.layout.widget_layout)
                    v.setTextViewText(R.id.tv_away, "오류")
                    v.setTextViewText(R.id.tv_home, "")
                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body  = response.body?.string() ?: return
                    val json  = JSONObject(body)
                    val games = json.getJSONArray("경기목록")
                    val v     = RemoteViews(context.packageName, R.layout.widget_layout)

                    if (games.length() == 0) {
                        v.setTextViewText(R.id.tv_away,      "오늘")
                        v.setTextViewText(R.id.tv_home,      "경기없음")
                        v.setTextViewText(R.id.tv_game_time, "")
                        v.setTextViewText(R.id.tv_stadium,   "")
                        v.setTextViewText(R.id.tv_channel,   "")
                        v.setTextViewText(R.id.tv_vs,        "")
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)
                        return
                    }

                    val game      = games.getJSONObject(0)
                    val away      = game.getString("away")
                    val home      = game.getString("home")
                    val gameTime  = game.getString("time")
                    val stadium   = game.optString("stadium", "")
                    val broadcast = game.optString("broadcast", "")
                    val awayLogo  = game.optString("away_logo", "")
                    val homeLogo  = game.optString("home_logo", "")

                    v.setTextViewText(R.id.tv_away,      away)
                    v.setTextViewText(R.id.tv_home,      home)
                    v.setTextViewText(R.id.tv_game_time, "$gameTime 시작")
                    v.setTextViewText(R.id.tv_stadium,   stadium)
                    v.setViewVisibility(R.id.tv_score_away,  android.view.View.GONE)
                    v.setViewVisibility(R.id.tv_score_home,  android.view.View.GONE)
                    v.setViewVisibility(R.id.tv_live_inning, android.view.View.GONE)

                    // 일단 원형 로고로 먼저 표시
                    val awayColor = teamColors[away] ?: 0xFF444444.toInt()
                    val homeColor = teamColors[home] ?: 0xFF444444.toInt()
                    v.setImageViewBitmap(R.id.iv_logo_away, makeCircleBitmap(away, awayColor))
                    v.setImageViewBitmap(R.id.iv_logo_home, makeCircleBitmap(home, homeColor))
                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v)

                    // SVG 로고 이미지 로드
                    if (awayLogo.isNotEmpty()) {
                        loadLogoBitmap(awayLogo) { bmp ->
                            bmp?.let {
                                val v2 = RemoteViews(context.packageName, R.layout.widget_layout)
                                v2.setImageViewBitmap(R.id.iv_logo_away, it)
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v2)
                            }
                        }
                    }
                    if (homeLogo.isNotEmpty()) {
                        loadLogoBitmap(homeLogo) { bmp ->
                            bmp?.let {
                                val v2 = RemoteViews(context.packageName, R.layout.widget_layout)
                                v2.setImageViewBitmap(R.id.iv_logo_home, it)
                                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, v2)
                            }
                        }
                    }

                    // 채널 정보
                    if (iptv.isNotEmpty()) {
                        if (broadcast.isNotEmpty()) {
                            fetchChannel(context, appWidgetManager, appWidgetId, iptv, broadcast)
                        } else {
                            fetchBothChannels(context, appWidgetManager, appWidgetId, iptv)
                        }
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
            "mbc_sports"   -> "MBC스포츠+"
            "kbs_n_sports" -> "KBS N스포츠"
            else           -> broadcaster.uppercase()
        }
        OkHttpClient().newCall(Request.Builder()
            .url("https://web-production-6aae76.up.railway.app/api/channel?iptv=$iptv&broadcaster=$broadcaster")
            .build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val ch = JSONObject(response.body?.string() ?: "{}").optString("채널번호", "")
                val v  = RemoteViews(context.packageName, R.layout.widget_layout)
                v.setTextViewText(R.id.tv_channel, "$name  ${ch}ch")
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