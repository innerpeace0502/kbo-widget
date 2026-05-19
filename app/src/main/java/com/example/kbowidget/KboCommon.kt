package com.example.kbowidget

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 두 위젯과 메인 앱이 공유하는 공통 유틸.
 *
 * - 04:00 기준 게임 날짜 계산 (서버 `get_game_date()`와 동일 로직)
 * - 날짜 변경 시 모든 캐시 일괄 클리어 (slim_/reg_/app_/game_)
 */
object KboCommon {

    const val PREFS_NAME = "kbo_prefs"

    /** 04:00 기준 게임 날짜 ("yyyyMMdd"). 00:00~03:59 시간대는 어제로 취급. */
    fun getGameDate(): String {
        val now = Calendar.getInstance()
        if (now.get(Calendar.HOUR_OF_DAY) < 4) now.add(Calendar.DAY_OF_MONTH, -1)
        return SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(now.time)
    }

    /** SharedPreferences 핸들 */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 저장된 게임 날짜가 현재 게임 날짜와 다르면 모든 스코어/스케줄 캐시 일괄 클리어.
     * 04:00 직후 자동으로 어제 종료 스코어를 지워 다음 경기로 전환.
     *
     * SharedPreferences 뿐 아니라 MainActivity의 in-memory companion object 캐시도
     * 함께 리셋해야 stale 데이터가 화면에 표시되지 않음.
     *
     * @return 클리어가 수행됐는지 여부
     */
    fun clearCacheIfDateChanged(context: Context): Boolean {
        val prefs = prefs(context)
        val saved = prefs.getString("kbo_game_date", "") ?: ""
        val today = getGameDate()
        if (saved == today) return false

        val editor = prefs.edit()
        // slim_*, reg_*, app_* 모든 캐시 prefix 일괄 클리어
        listOf(
            "slim_status", "slim_away_score", "slim_home_score", "slim_inning", "slim_stadium", "slim_date",
            "reg_status",  "reg_away_score",  "reg_home_score",  "reg_inning",  "reg_date",   "reg_away",  "reg_home",
            "app_status",  "app_away_score",  "app_home_score",  "app_inning",  "app_date",   "app_away",  "app_home",
            "app_away_pitchers", "app_home_pitchers",
            "app_sched_date_key", "app_sched_away", "app_sched_home", "app_sched_team",
            "app_sched_date_display", "app_sched_time", "app_sched_stadium",
            "app_sched_broadcast", "app_sched_last_load"
        ).forEach { editor.remove(it) }
        editor.putString("kbo_game_date", today)
        editor.apply()

        // ✅ MainActivity in-memory 캐시도 함께 리셋 (stale 0:2 표시 방지)
        MainActivity.resetStaleScoreCache()

        // 로고 비트맵 캐시는 유지 (팀 변경 시에만 클리어)
        return true
    }

    /** 두 위젯 모두 한 번에 갱신 트리거 (메인 앱의 "저장" 동작용) */
    fun triggerWidgetRefresh(context: Context) {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val regularIds = manager.getAppWidgetIds(
            android.content.ComponentName(context, KboWidgetProvider::class.java)
        )
        val slimIds = manager.getAppWidgetIds(
            android.content.ComponentName(context, KboWidgetProviderSlim::class.java)
        )
        if (regularIds.isNotEmpty()) {
            context.sendBroadcast(android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = android.content.ComponentName(context, KboWidgetProvider::class.java)
                putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, regularIds)
            })
        }
        if (slimIds.isNotEmpty()) {
            context.sendBroadcast(android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = android.content.ComponentName(context, KboWidgetProviderSlim::class.java)
                putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, slimIds)
            })
        }
    }
}
