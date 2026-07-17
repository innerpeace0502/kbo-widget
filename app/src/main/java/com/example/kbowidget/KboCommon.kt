package com.example.kbowidget

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

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
     * 두 위젯·앱이 공유하는 단일 OkHttp 클라이언트.
     * 호출마다 OkHttpClient()를 새로 만들면 커넥션 풀·스레드 풀이 매번 생성돼
     * keep-alive 재사용이 안 되고(TLS 핸드셰이크 반복) 메모리만 낭비된다.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 위젯 갱신 알람 주기 — 오늘 경기가 종료(2)/취소(3)로 확정되면 10분으로 완화.
     * 다음 04:00(게임 날짜 변경 → clearCacheIfDateChanged가 캐시 클리어)에 60초로 자동 복귀.
     * 완전 중단이 아닌 '완화'인 이유: 더블헤더 2차전 등 같은 날 추가 경기를
     * 최대 10분 지연으로는 잡아내기 위함. 종료 후 밤새 60초 폴링하던 배터리 낭비 제거.
     */
    fun fetchIntervalMs(context: Context): Long {
        val prefs = prefs(context)
        val today = getGameDate()
        val ended = listOf("reg", "app", "slim").any { pfx ->
            val st = prefs.getString("${pfx}_status", "") ?: ""
            val dt = prefs.getString("${pfx}_date", "") ?: ""
            dt == today && (st == "2" || st == "3")
        }
        return if (ended) 10 * 60_000L else 60_000L
    }

    /**
     * 일회성 마이그레이션 — 옛 위젯이 자정~04:00 사이에 SimpleDateFormat(단순 날짜)
     * 키로 어제 데이터를 reg_/app_/slim_에 "오늘"인 양 잘못 저장한 stale 캐시를 한 번에 청소.
     * 이 버전부터 모든 todayStr 계산을 04:00 컷오프(getGameDate)로 통일하므로
     * 청소 후엔 같은 문제가 재발하지 않는다.
     */
    fun runMigrationV2IfNeeded(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean("prefs_migration_v2_done", false)) return
        val editor = prefs.edit()
        listOf(
            "slim_status", "slim_away_score", "slim_home_score", "slim_inning", "slim_stadium", "slim_date",
            "reg_status",  "reg_away_score",  "reg_home_score",  "reg_inning",  "reg_date",   "reg_away",  "reg_home",
            "app_status",  "app_away_score",  "app_home_score",  "app_inning",  "app_date",   "app_away",  "app_home"
        ).forEach { editor.remove(it) }
        editor.putBoolean("prefs_migration_v2_done", true)
        editor.apply()
        MainActivity.resetStaleScoreCache()
    }

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
        // ✅ 위젯/앱 어디서 호출되든 마이그레이션이 한 번은 보장되도록 첫 줄에서 트리거.
        runMigrationV2IfNeeded(context)
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
            "app_sched_broadcast", "app_sched_last_load", "app_sched_is_future"
        ).forEach { editor.remove(it) }
        editor.putString("kbo_game_date", today)
        editor.apply()

        // ✅ MainActivity in-memory 캐시도 함께 리셋 (stale 0:2 표시 방지)
        MainActivity.resetStaleScoreCache()

        // ✅ 위젯 RemoteViews 강제 갱신 — 휴대폰 재부팅 등으로 위젯 onUpdate 알람이
        // 막혀 화면이 전날 상태로 멈춰있던 문제 방지.
        triggerWidgetRefresh(context)

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
