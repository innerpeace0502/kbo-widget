package com.example.kbowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerTeam: Spinner
    private lateinit var spinnerIptv: Spinner
    private lateinit var seekbarAlpha: android.widget.SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var layoutGameInfo: LinearLayout
    private lateinit var tvGameDate: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var layoutMainCard: LinearLayout
    private lateinit var ivScoreAwayLogo: ImageView
    private lateinit var ivScoreHomeLogo: ImageView
    private lateinit var tvScoreAwayName: TextView
    private lateinit var tvScoreHomeName: TextView
    private lateinit var tvScoreAwayScore: TextView
    private lateinit var tvScoreHomeScore: TextView
    private lateinit var tvScoreInning: TextView
    private lateinit var tvGameTimeInfo: TextView
    private lateinit var tvStadiumInfo: TextView
    private lateinit var tvChannelInfo: TextView
    private lateinit var layoutAwayPitcher: LinearLayout
    private lateinit var layoutHomePitcher: LinearLayout
    private lateinit var tvAwayRankLabel: TextView
    private lateinit var tvHomeRankLabel: TextView
    private lateinit var tvAwayRank: TextView
    private lateinit var tvHomeRank: TextView
    private lateinit var tvAwayRecord: TextView
    private lateinit var tvHomeRecord: TextView
    private lateinit var tvAwayRecentRow1: LinearLayout
    private lateinit var tvAwayRecentRow2: LinearLayout
    private lateinit var tvHomeRecentRow1: LinearLayout
    private lateinit var tvHomeRecentRow2: LinearLayout
    private lateinit var layoutFullRanking: LinearLayout
    private lateinit var layoutRankingContainer: LinearLayout

    private val BASE = "https://web-production-6aae76.up.railway.app"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 경기 중 자동 갱신 루프
    private val scoreHandler = Handler(Looper.getMainLooper())
    private var scoreRunnable: Runnable? = null
    private val SCORE_REFRESH_INTERVAL = 30_000L  // 30초

    companion object {
        var lastLoadTime     = 0L
        var cachedTeam       = ""
        var cachedAway       = ""
        var cachedHome       = ""
        var cachedDate       = ""
        var cachedTime       = ""
        var cachedStadium    = ""
        var cachedBroadcast  = ""
        var cachedAwayScore  = ""
        var cachedHomeScore  = ""
        var cachedInning     = ""
        var cachedStatus     = ""
        var cachedAwayRank   = ""
        var cachedHomeRank   = ""
        var cachedAwayRecord = ""
        var cachedHomeRecord = ""
        var cachedAwayRecent: List<String> = emptyList()
        var cachedHomeRecent: List<String> = emptyList()
        // ✅ 투수/타자 캐시 (JSON 배열 String 형태로 저장 — 앱 재진입 시 사라짐 버그 수정)
        var cachedAwayPitchers: String = ""
        var cachedHomePitchers: String = ""
        var cachedAwayLogoBitmap: Bitmap? = null
        var cachedHomeLogoBitmap: Bitmap? = null
        var cachedRankingList: List<Map<String, String>> = emptyList()
        var cachedRankingTime = 0L
        val RANKING_TTL = 10 * 60 * 1000L
        const val CACHE_TTL = 3 * 60 * 1000L

        // 양팀 순위 카드의 팀명 칩 배경 색상 (KboWidgetProvider와 동일 매핑)
        val TEAM_COLORS = mapOf(
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

        /**
         * 날짜 변경 시 in-memory 스코어 캐시를 강제 리셋.
         * KboCommon.clearCacheIfDateChanged가 SharedPreferences를 클리어할 때 함께 호출되어,
         * companion object에 어제(또는 이전 시점)의 stale 스코어가 남아 표시되는 것을 방지.
         */
        fun resetStaleScoreCache() {
            cachedAwayScore = ""
            cachedHomeScore = ""
            cachedInning    = ""
            cachedStatus    = ""
            // ✅ 투수/타자 캐시도 함께 리셋 (날짜 바뀌면 어제 투수 stale)
            cachedAwayPitchers = ""
            cachedHomePitchers = ""
            lastLoadTime    = 0L
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        // ✅ 일회성 마이그레이션 — 옛 SimpleDateFormat(단순 날짜) 기반으로 자정~04:00에
        // 잘못 저장된 어제 캐시를 청소. 한 번 실행되면 prefs_migration_v2_done 플래그로 스킵.
        KboCommon.runMigrationV2IfNeeded(this)

        spinnerTeam            = findViewById(R.id.spinner_team)
        spinnerIptv            = findViewById(R.id.spinner_iptv)
        seekbarAlpha           = findViewById(R.id.seekbar_alpha)
        tvAlphaValue           = findViewById(R.id.tv_alpha_value)
        btnSave                = findViewById(R.id.btn_save)
        tvStatus               = findViewById(R.id.tv_status)
        layoutGameInfo         = findViewById(R.id.layout_game_info)
        tvGameDate             = findViewById(R.id.tv_game_date)
        tvStatusBadge          = findViewById(R.id.tv_status_badge)
        layoutMainCard         = findViewById(R.id.layout_main_card)
        ivScoreAwayLogo        = findViewById(R.id.iv_score_away_logo)
        ivScoreHomeLogo        = findViewById(R.id.iv_score_home_logo)
        tvScoreAwayName        = findViewById(R.id.tv_score_away_name)
        tvScoreHomeName        = findViewById(R.id.tv_score_home_name)
        tvScoreAwayScore       = findViewById(R.id.tv_score_away_score)
        tvScoreHomeScore       = findViewById(R.id.tv_score_home_score)
        tvScoreInning          = findViewById(R.id.tv_score_inning)
        tvGameTimeInfo         = findViewById(R.id.tv_game_time_info)
        tvStadiumInfo          = findViewById(R.id.tv_stadium_info)
        tvChannelInfo          = findViewById(R.id.tv_channel_info)
        layoutAwayPitcher      = findViewById(R.id.layout_away_pitcher)
        layoutHomePitcher      = findViewById(R.id.layout_home_pitcher)
        tvAwayRankLabel        = findViewById(R.id.tv_away_rank_label)
        tvHomeRankLabel        = findViewById(R.id.tv_home_rank_label)
        tvAwayRank             = findViewById(R.id.tv_away_rank)
        tvHomeRank             = findViewById(R.id.tv_home_rank)
        tvAwayRecord           = findViewById(R.id.tv_away_record)
        tvHomeRecord           = findViewById(R.id.tv_home_record)
        tvAwayRecentRow1       = findViewById(R.id.tv_away_recent_row1)
        tvAwayRecentRow2       = findViewById(R.id.tv_away_recent_row2)
        tvHomeRecentRow1       = findViewById(R.id.tv_home_recent_row1)
        tvHomeRecentRow2       = findViewById(R.id.tv_home_recent_row2)
        layoutFullRanking      = findViewById(R.id.layout_full_ranking)
        layoutRankingContainer = findViewById(R.id.layout_ranking_container)

        val teams    = listOf("전체","LG","KT","SSG","NC","두산","KIA","롯데","삼성","한화","키움")
        val iptvList = listOf("선택안함","지니TV (KT)","U+TV (LGU+)","BTV (SKT)")
        spinnerTeam.adapter = makeYellowAdapter(teams)
        spinnerIptv.adapter = makeYellowAdapter(iptvList)

        val savedTeam = prefs.getString("team", "전체") ?: "전체"
        val savedIptv = prefs.getString("iptv", "") ?: ""
        spinnerTeam.setSelection(teams.indexOf(savedTeam).coerceAtLeast(0))
        spinnerIptv.setSelection(when (savedIptv) {
            "genie" -> 1; "Uplus" -> 2; "btv" -> 3; else -> 0
        })

        // ✅ 투명도 슬라이더 초기화
        val savedAlpha = prefs.getInt("bg_alpha", 94)
        seekbarAlpha.progress = savedAlpha
        tvAlphaValue.text = "$savedAlpha%"
        seekbarAlpha.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val prog = sb?.progress ?: return
                prefs.edit().putInt("bg_alpha", prog).apply()
            }
        })

        btnSave.setOnClickListener {
            val selectedTeam  = spinnerTeam.selectedItem.toString()
            val selectedIptv  = when (spinnerIptv.selectedItemPosition) {
                1 -> "genie"; 2 -> "Uplus"; 3 -> "btv"; else -> ""
            }
            val selectedAlpha = seekbarAlpha.progress
            prefs.edit()
                .putString("team", selectedTeam)
                .putString("iptv", selectedIptv)
                .putInt("bg_alpha", selectedAlpha)
                .apply()

            val manager = AppWidgetManager.getInstance(this)
            val ids = manager.getAppWidgetIds(ComponentName(this, KboWidgetProvider::class.java))
            val intent = android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(intent)
            val slimIds = manager.getAppWidgetIds(ComponentName(this, KboWidgetProviderSlim::class.java))
            if (slimIds.isNotEmpty()) {
                val slimIntent = android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                slimIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, slimIds)
                sendBroadcast(slimIntent)
            }

            tvStatus.text = "저장완료! 팀: $selectedTeam"
            Toast.makeText(this, "설정이 저장됐어요!", Toast.LENGTH_SHORT).show()

            if (selectedTeam != cachedTeam) {
                lastLoadTime         = 0L
                cachedTeam           = selectedTeam
                cachedAway           = ""
                cachedHome           = ""
                cachedAwayScore      = ""
                cachedHomeScore      = ""
                cachedInning         = ""
                cachedStatus         = ""
                cachedAwayRank       = ""
                cachedHomeRank       = ""
                cachedAwayRecord     = ""
                cachedHomeRecord     = ""
                cachedAwayRecent     = emptyList()
                cachedHomeRecent     = emptyList()
                cachedAwayLogoBitmap = null
                cachedHomeLogoBitmap = null
            }
            fetchGameInfo(selectedTeam, selectedIptv)
        }

        // ✅ 설정 영역 접기/펼치기 토글 (펼침=◀, 접힘=▼, 상태는 prefs에 저장되어 앱 재실행에도 유지)
        val btnToggleSettings  = findViewById<android.widget.TextView>(R.id.btn_toggle_settings)
        val layoutSettingsBody = findViewById<android.widget.LinearLayout>(R.id.layout_settings_body)
        fun applySettingsCollapsed(collapsed: Boolean) {
            layoutSettingsBody.visibility = if (collapsed) android.view.View.GONE else android.view.View.VISIBLE
            btnToggleSettings.text = if (collapsed) "▼" else "◀"
        }
        applySettingsCollapsed(prefs.getBoolean("settings_collapsed", false))  // 기본 펼침
        btnToggleSettings.setOnClickListener {
            val collapsed = !prefs.getBoolean("settings_collapsed", false)
            prefs.edit().putBoolean("settings_collapsed", collapsed).apply()
            applySettingsCollapsed(collapsed)
        }

        // ✅ 04:00 기준 게임 날짜 변경 시 캐시 일괄 클리어
        KboCommon.clearCacheIfDateChanged(this)
        // ✅ 앱을 열 때마다 위젯도 강제 갱신 — 위젯 onUpdate 알람이 막혀(배터리 최적화 등)
        // 화면이 stale일 때, 앱을 한 번 열기만 하면 위젯이 즉시 새 데이터로 다시 그려진다.
        KboCommon.triggerWidgetRefresh(this)
        restoreScheduleFromPrefs()   // 프로세스 재시작 시 캐시 복원
        loadGameInfo(savedTeam, savedIptv)
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val team  = prefs.getString("team", "전체") ?: "전체"
        val iptv  = prefs.getString("iptv", "") ?: ""

        // 캐시된 날짜와 오늘 날짜 비교
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .format(java.util.Calendar.getInstance().time)

        // 위젯 터치 등 프로세스 재시작 후 캐시가 비어있으면 SharedPreferences에서 복원
        if (cachedAway.isEmpty()) restoreScheduleFromPrefs()

        if (cachedAway.isNotEmpty()) restoreCachedUI()

        if (cachedDate.isEmpty() || !cachedDate.contains(today)) {
            // 날짜가 바뀌었거나 캐시 없음 → 전체 갱신
            lastLoadTime = 0L
            fetchGameInfo(team, iptv)
        } else if (cachedStatus == "1") {
            // 경기 중 → 스코어 갱신 + 루프 시작
            val myTeam = if (cachedTeam == "전체") "" else cachedTeam
            loadScores(cachedAway, cachedHome, myTeam)
            startScoreLoop()
        } else if (cachedStatus == "2" && cachedAway.isNotEmpty()) {
            // 경기 종료 → 최신 최종 스코어로 1회 갱신 (루프 없음)
            val myTeam = if (cachedTeam == "전체") "" else cachedTeam
            loadScores(cachedAway, cachedHome, myTeam)
        }
        // 경기 전 상태이고 오늘 날짜면 캐시 그대로 표시
    }

    override fun onPause() {
        super.onPause()
        stopScoreLoop()
    }

    private fun startScoreLoop() {
        stopScoreLoop()  // 중복 방지
        scoreRunnable = object : Runnable {
            override fun run() {
                if (cachedAway.isNotEmpty()) {
                    val myTeam = if (cachedTeam == "전체") "" else cachedTeam
                    loadScores(cachedAway, cachedHome, myTeam)
                }
                // 경기 중일 때만 반복
                if (cachedStatus == "1") {
                    scoreHandler.postDelayed(this, SCORE_REFRESH_INTERVAL)
                }
            }
        }
        scoreHandler.postDelayed(scoreRunnable!!, SCORE_REFRESH_INTERVAL)
    }

    private fun stopScoreLoop() {
        scoreRunnable?.let { scoreHandler.removeCallbacks(it) }
        scoreRunnable = null
    }

    private fun restoreCachedUI() {
        if (cachedAway.isEmpty()) return
        layoutGameInfo.visibility = View.VISIBLE
        tvGameDate.text       = cachedDate
        tvScoreAwayName.text  = cachedAway
        tvScoreHomeName.text  = cachedHome
        tvStadiumInfo.text    = cachedStadium
        applyTeamChip(tvAwayRankLabel, cachedAway)
        applyTeamChip(tvHomeRankLabel, cachedHome)
        if (cachedAwayRank.isNotEmpty())   tvAwayRank.text   = cachedAwayRank
        if (cachedHomeRank.isNotEmpty())   tvHomeRank.text   = cachedHomeRank
        if (cachedAwayRecord.isNotEmpty()) tvAwayRecord.text = cachedAwayRecord
        if (cachedHomeRecord.isNotEmpty()) tvHomeRecord.text = cachedHomeRecord
        if (cachedAwayRecent.isNotEmpty()) updateRecentView(tvAwayRecentRow1, tvAwayRecentRow2, cachedAwayRecent)
        if (cachedHomeRecent.isNotEmpty()) updateRecentView(tvHomeRecentRow1, tvHomeRecentRow2, cachedHomeRecent)
        // ✅ 투수/타자 영역 복원 (앱 재진입 시 사라짐 버그 수정)
        if (cachedAwayPitchers.isNotEmpty()) {
            try {
                layoutAwayPitcher.removeAllViews()
                buildPitcherView(layoutAwayPitcher, JSONArray(cachedAwayPitchers))
            } catch (_: Exception) {}
        }
        if (cachedHomePitchers.isNotEmpty()) {
            try {
                layoutHomePitcher.removeAllViews()
                buildPitcherView(layoutHomePitcher, JSONArray(cachedHomePitchers))
            } catch (_: Exception) {}
        }
        updateScoreCard(cachedAwayScore, cachedHomeScore, cachedInning, cachedStatus)
        cachedAwayLogoBitmap?.let { ivScoreAwayLogo.setImageBitmap(it) }
        cachedHomeLogoBitmap?.let { ivScoreHomeLogo.setImageBitmap(it) }
        if (cachedRankingList.isNotEmpty()) {
            layoutFullRanking.visibility = View.VISIBLE
            renderRanking(cachedRankingList, cachedAway, cachedHome,
                if (cachedTeam == "전체") "" else cachedTeam)
        }
    }

    private fun makeYellowAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.parseColor("#FFD700"))
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                return view
            }
        }
    }

    private fun loadGameInfo(team: String, iptv: String) {
        val now = System.currentTimeMillis()
        if (now - lastLoadTime < CACHE_TTL && cachedAway.isNotEmpty() && team == cachedTeam) {
            restoreCachedUI()
            return
        }
        fetchGameInfo(team, iptv)
    }

    private fun fetchGameInfo(team: String, iptv: String) {
        val teamParam = if (team == "전체") "" else "?team=$team"
        client.newCall(
            Request.Builder().url("$BASE/api/schedule/today$teamParam").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { if (cachedAway.isNotEmpty()) restoreCachedUI() }
            }
            override fun onResponse(call: Call, response: Response) {
                val body  = response.body?.string() ?: return
                val json  = JSONObject(body)
                val games = json.getJSONArray("경기목록")
                val date  = json.getString("날짜")
                val isTomorrow  = json.optBoolean("내일경기", false)
                val isCancelled = json.optBoolean("cancelled", false)

                runOnUiThread {
                    if (games.length() == 0) {
                        layoutGameInfo.visibility = View.GONE
                        tvStatus.text = "오늘 경기가 없습니다"
                        return@runOnUiThread
                    }

                    val game      = games.getJSONObject(0)
                    val away      = game.getString("away")
                    val home      = game.getString("home")
                    val time      = game.getString("time")
                    val stadium   = game.optString("stadium", "")
                    val broadcast = game.optString("broadcast", "")

                    cachedTeam      = team
                    cachedAway      = away
                    cachedHome      = home
                    cachedDate      = if (isTomorrow) "내일 경기 예정" else date
                    cachedTime      = time
                    cachedStadium   = stadium
                    cachedBroadcast = broadcast
                    lastLoadTime    = System.currentTimeMillis()

                    // ✅ 경기 메타데이터 저장 → 위젯 터치·프로세스 재시작 후 즉시 복원
                    val todayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                        .format(java.util.Calendar.getInstance().time)
                    getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE).edit()
                        .putString("app_sched_date_key",     todayKey)
                        .putString("app_sched_away",         away)
                        .putString("app_sched_home",         home)
                        .putString("app_sched_team",         team)
                        .putString("app_sched_date_display", cachedDate)
                        .putString("app_sched_time",         time)
                        .putString("app_sched_stadium",      stadium)
                        .putString("app_sched_broadcast",    broadcast)
                        .putLong(  "app_sched_last_load",    lastLoadTime)
                        .apply()

                    layoutGameInfo.visibility = View.VISIBLE
                    tvGameDate.text      = cachedDate
                    tvScoreAwayName.text = away
                    tvScoreHomeName.text = home
                    tvStadiumInfo.text   = stadium
                    applyTeamChip(tvAwayRankLabel, away)
                    applyTeamChip(tvHomeRankLabel, home)

                    // 경기종료 캐시 있으면 즉시 표시, 없으면 초기화
                    if (cachedStatus == "2" && cachedAwayScore.isNotEmpty()) {
                        updateScoreCard(cachedAwayScore, cachedHomeScore, "경기종료", "2")
                    } else {
                        tvGameTimeInfo.text  = "$time 시작"
                        tvGameTimeInfo.visibility = View.VISIBLE
                        tvScoreAwayScore.visibility = View.GONE
                        tvScoreHomeScore.visibility = View.GONE
                        tvScoreInning.visibility    = View.GONE
                        tvStatusBadge.visibility    = View.GONE
                    }

                    // 경기 예정: 가운데 VS 표시
                    if (isTomorrow) {
                        tvScoreInning.text = "VS"
                        tvScoreInning.setTextColor(Color.parseColor("#555555"))
                        tvScoreInning.setBackgroundColor(Color.TRANSPARENT)
                        tvScoreInning.textSize = 16f
                        tvScoreInning.visibility = View.VISIBLE
                    }

                    val chName = broadcastName(broadcast)
                    if (iptv.isNotEmpty() && broadcast.isNotEmpty() && broadcast != "tving") {
                        fetchChannelNumber(iptv, broadcast, chName)
                    } else {
                        tvChannelInfo.text = chName
                    }

                    val teamParam2 = if (team == "전체") away else team
                    val myTeam    = if (team == "전체") "" else team

                    // ✅ 전체 순위 항상 표시
                    loadRanking(away, home, myTeam)
                    loadPitcherInfo(teamParam2)
                    loadRecentGames(away, home)
                    loadScores(away, home, myTeam)
                    loadLogos(away, home)
                }
            }
        })
    }

    private fun loadScores(away: String, home: String, myTeam: String) {
        val prefs     = getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val todayStr  = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
            .format(java.util.Calendar.getInstance().time)

        client.newCall(
            Request.Builder().url("$BASE/api/scores").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                restorePersistedScore(away, home, myTeam, prefs, todayStr)
            }
            override fun onResponse(call: Call, response: Response) {
                val body   = response.body?.string() ?: return
                val json   = JSONObject(body)
                val scores = json.getJSONArray("scores")
                var found  = false
                for (i in 0 until scores.length()) {
                    val s     = scores.getJSONObject(i)
                    val sAway = s.getString("away")
                    val sHome = s.getString("home")
                    if ((sAway == away && sHome == home) ||
                        (sAway == home && sHome == away)) {
                        found = true
                        val status    = s.getString("status")
                        val awayScore = s.optString("away_score", "")
                        val homeScore = s.optString("home_score", "")
                        val inning    = s.optString("inning", "")
                        cachedAwayScore = awayScore
                        cachedHomeScore = homeScore
                        cachedInning    = inning
                        cachedStatus    = status
                        // ✅ 스코어를 SharedPreferences에 저장 (앱 재시작 복원용)
                        if ((status == "1" || status == "2") && awayScore.isNotEmpty()) {
                            prefs.edit()
                                .putString("app_status",     status)
                                .putString("app_away_score", awayScore)
                                .putString("app_home_score", homeScore)
                                .putString("app_inning",     inning)
                                .putString("app_date",       todayStr)
                                .putString("app_away",       away)
                                .putString("app_home",       home)
                                .apply()
                        } else if (status == "3") {
                            // 우천취소: 점수는 없지만 취소 상태 자체를 저장 (재진입 시 예정 오인 방지)
                            prefs.edit()
                                .putString("app_status",     "3")
                                .putString("app_away_score", "")
                                .putString("app_home_score", "")
                                .putString("app_inning",     inning)
                                .putString("app_date",       todayStr)
                                .putString("app_away",       away)
                                .putString("app_home",       home)
                                .apply()
                        }
                        runOnUiThread {
                            updateScoreCard(awayScore, homeScore, inning, status)
                            if (status == "2") {
                                layoutFullRanking.visibility = View.VISIBLE
                                if (cachedRankingList.isNotEmpty()) {
                                    renderRanking(cachedRankingList, away, home, myTeam)
                                }
                            }
                        }
                        break
                    }
                }
                if (!found) {
                    restorePersistedScore(away, home, myTeam, prefs, todayStr)
                }
            }
        })
    }

    private fun restorePersistedScore(
        away: String, home: String, myTeam: String,
        prefs: android.content.SharedPreferences, todayStr: String
    ) {
        // SharedPreferences → 인메모리 캐시 순으로 폴백
        val pStatus = prefs.getString("app_status", "") ?: ""
        val pAway   = prefs.getString("app_away", "") ?: ""
        val pHome   = prefs.getString("app_home", "") ?: ""
        val pDate   = prefs.getString("app_date", "") ?: ""
        val pAScore = prefs.getString("app_away_score", "") ?: ""
        val pHScore = prefs.getString("app_home_score", "") ?: ""

        val matchesPersist = (pStatus == "1" || pStatus == "2") && pAScore.isNotEmpty() && pDate == todayStr &&
            ((pAway == away && pHome == home) || (pAway == home && pHome == away))
        // ✅ in-memory 캐시도 현재 표시 중인 경기 팀과 일치해야 사용 (stale 방지)
        val matchesCache   = cachedStatus == "2" && cachedAwayScore.isNotEmpty()
            && cachedAway == away && cachedHome == home

        if (matchesPersist || matchesCache) {
            val aScore = if (matchesPersist) pAScore else cachedAwayScore
            val hScore = if (matchesPersist) pHScore else cachedHomeScore
            if (matchesPersist) {
                cachedAwayScore = aScore; cachedHomeScore = hScore
                cachedInning = "경기종료"; cachedStatus = "2"
            }
            runOnUiThread {
                updateScoreCard(aScore, hScore, "경기종료", "2")
                layoutFullRanking.visibility = View.VISIBLE
                if (cachedRankingList.isNotEmpty()) {
                    renderRanking(cachedRankingList, away, home, myTeam)
                }
            }
        }
    }

    private fun updateScoreCard(
        awayScore: String, homeScore: String,
        inning: String, status: String
    ) {
        when (status) {
            "1" -> {
                // 경기 중
                tvScoreAwayScore.text = awayScore
                tvScoreHomeScore.text = homeScore
                tvScoreAwayScore.visibility = View.VISIBLE
                tvScoreHomeScore.visibility = View.VISIBLE
                // 이닝 배지: 빨간 배경
                tvScoreInning.text = inning
                tvScoreInning.setTextColor(Color.parseColor("#FF6B6B"))
                applyBadgeBackground(tvScoreInning, "#261010", "#4D1515")
                tvScoreInning.textSize = 11f
                tvScoreInning.visibility = View.VISIBLE
                tvGameTimeInfo.visibility = View.VISIBLE
                // 상단 상태 배지
                tvStatusBadge.text = "● $inning"
                tvStatusBadge.setTextColor(Color.parseColor("#FF6B6B"))
                applyBadgeBackground(tvStatusBadge, "#261010", "#4D1515")
                tvStatusBadge.visibility = View.VISIBLE
                layoutFullRanking.visibility = View.VISIBLE
                // 경기 중 자동 갱신 루프 시작
                if (scoreRunnable == null) startScoreLoop()
            }
            "2" -> {
                // 경기 종료
                tvScoreAwayScore.text = awayScore
                tvScoreHomeScore.text = homeScore
                tvScoreAwayScore.visibility = View.VISIBLE
                tvScoreHomeScore.visibility = View.VISIBLE
                tvScoreInning.text = "경기종료"
                tvScoreInning.setTextColor(Color.parseColor("#999999"))
                applyBadgeBackground(tvScoreInning, "#1A1A1A", "#2A2A2A")
                tvScoreInning.textSize = 10f
                tvScoreInning.visibility = View.VISIBLE
                tvGameTimeInfo.visibility = View.GONE
                // 상단 상태 배지
                tvStatusBadge.text = "경기종료"
                tvStatusBadge.setTextColor(Color.parseColor("#999999"))
                applyBadgeBackground(tvStatusBadge, "#1A1A1A", "#2A2A2A")
                tvStatusBadge.visibility = View.VISIBLE
                layoutFullRanking.visibility = View.VISIBLE
                // 경기 종료 → 루프 중지
                stopScoreLoop()
            }
            "3" -> {
                // 우천취소 (경기취소) — 점수 숨기고 취소 배지 표시
                tvScoreAwayScore.visibility = View.GONE
                tvScoreHomeScore.visibility = View.GONE
                val cancelText = if (inning.isNotEmpty()) inning else "우천취소"
                tvScoreInning.text = cancelText
                tvScoreInning.setTextColor(Color.parseColor("#8AB4F8"))  // 청회색 (비 연상)
                applyBadgeBackground(tvScoreInning, "#16202E", "#2A3D52")
                tvScoreInning.textSize = 11f
                tvScoreInning.visibility = View.VISIBLE
                tvGameTimeInfo.visibility = View.GONE  // "시작 시간" 숨김 (예정 오인 방지)
                // 상단 상태 배지
                tvStatusBadge.text = cancelText
                tvStatusBadge.setTextColor(Color.parseColor("#8AB4F8"))
                applyBadgeBackground(tvStatusBadge, "#16202E", "#2A3D52")
                tvStatusBadge.visibility = View.VISIBLE
                layoutFullRanking.visibility = View.VISIBLE
                // 취소 → 루프 중지
                stopScoreLoop()
            }
            else -> {
                // 경기 전 (내일경기 or 예정)
                tvScoreAwayScore.visibility = View.GONE
                tvScoreHomeScore.visibility = View.GONE
                tvGameTimeInfo.visibility = View.VISIBLE
                layoutFullRanking.visibility = View.VISIBLE
            }
        }
    }

    private fun applyBadgeBackground(view: TextView, bgColor: String, borderColor: String) {
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(Color.parseColor(bgColor))
        bg.setStroke(2, Color.parseColor(borderColor))
        bg.cornerRadius = 14f
        view.background = bg
    }

    private fun loadLogos(away: String, home: String) {
        fun loadLogo(team: String, size: Int, onLoaded: (Bitmap) -> Unit) {
            client.newCall(Request.Builder().url("$BASE/logos/$team").build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        val bytes = response.body?.bytes() ?: return
                        val raw   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                        val bmp   = Bitmap.createScaledBitmap(raw, size, size, true)
                        // 원형 크롭
                        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(output)
                        val paint  = android.graphics.Paint().apply { isAntiAlias = true }
                        canvas.drawCircle(size/2f, size/2f, size/2f, paint)
                        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                        canvas.drawBitmap(bmp, 0f, 0f, paint)
                        runOnUiThread { onLoaded(output) }
                    }
                })
        }
        val px = (46 * resources.displayMetrics.density).toInt()
        loadLogo(away, px) { bmp -> cachedAwayLogoBitmap = bmp; ivScoreAwayLogo.setImageBitmap(bmp) }
        loadLogo(home, px) { bmp -> cachedHomeLogoBitmap = bmp; ivScoreHomeLogo.setImageBitmap(bmp) }
    }

    private fun broadcastName(broadcast: String): String = when (broadcast) {
        "spotv" -> "SPOTV"; "spotv2" -> "SPOTV2"
        "kbs_n_sports" -> "KBS N스포츠"; "mbc_sports" -> "MBC스포츠+"
        "sbs_sports" -> "SBS스포츠"; "kbs2" -> "KBS2"
        "mbc" -> "MBC"; "sbs" -> "SBS"; "tving" -> "TVING"
        else -> broadcast
    }

    private fun fetchChannelNumber(iptv: String, broadcast: String, chName: String) {
        client.newCall(
            Request.Builder().url("$BASE/api/channel?iptv=$iptv&broadcaster=$broadcast").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val ch = JSONObject(response.body?.string() ?: "{}").optString("채널번호", "")
                runOnUiThread {
                    tvChannelInfo.text = if (ch.isNotEmpty()) "$chName ${ch}ch" else chName
                }
            }
        })
    }

    private fun loadPitcherInfo(teamParam: String) {
        client.newCall(
            Request.Builder().url("$BASE/api/gameinfo?team=$teamParam").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val gameStatus   = json.optString("status", "")
                val awayPitchers = json.optJSONArray("away_pitchers") ?: JSONArray()
                val homePitchers = json.optJSONArray("home_pitchers") ?: JSONArray()

                // ✅ 앱 재진입 시 사라지지 않도록 투수/타자 JSON을 prefs + memory 캐시에 저장
                val awayPitchersStr = awayPitchers.toString()
                val homePitchersStr = homePitchers.toString()
                cachedAwayPitchers = awayPitchersStr
                cachedHomePitchers = homePitchersStr
                getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE).edit()
                    .putString("app_away_pitchers", awayPitchersStr)
                    .putString("app_home_pitchers", homePitchersStr)
                    .apply()

                runOnUiThread {
                    layoutAwayPitcher.removeAllViews()
                    layoutHomePitcher.removeAllViews()
                    buildPitcherView(layoutAwayPitcher, awayPitchers)
                    buildPitcherView(layoutHomePitcher, homePitchers)
                }
                // 경기 점수/상태의 단일 출처는 /api/scores. gameinfo의 'ended'는 폴백으로만 사용.
                // scores가 이미 라이브(1)/종료(2)를 확정했으면 덮어쓰지 않는다.
                // (라이브 중 gameinfo 'ended' 오판이 점수를 '경기종료'로 굳히고
                //  스코어 갱신 루프를 멈추던 버그 방지 — 이슈1)
                if (gameStatus == "ended" && cachedStatus != "1" && cachedStatus != "2") {
                    val awayScore = json.optString("away_score", "")
                    val homeScore = json.optString("home_score", "")
                    if (awayScore.isNotEmpty() && homeScore.isNotEmpty()) {
                        val away     = json.optString("away", cachedAway)
                        val home     = json.optString("home", cachedHome)
                        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
                            .format(java.util.Calendar.getInstance().time)
                        val prefs    = getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("app_status",     "2")
                            .putString("app_away_score", awayScore)
                            .putString("app_home_score", homeScore)
                            .putString("app_date",       todayStr)
                            .putString("app_away",       away)
                            .putString("app_home",       home)
                            .apply()
                        cachedAwayScore = awayScore
                        cachedHomeScore = homeScore
                        cachedInning    = "경기종료"
                        cachedStatus    = "2"
                        runOnUiThread {
                            updateScoreCard(awayScore, homeScore, "경기종료", "2")
                            layoutFullRanking.visibility = View.VISIBLE
                        }
                    }
                }
            }
        })
    }

    private fun buildPitcherView(container: LinearLayout, pitchers: JSONArray) {
        for (i in 0 until pitchers.length()) {
            val p     = pitchers.getJSONObject(i)
            val label = p.optString("label", "")
            val name  = p.optString("name", "")
            if (name.isEmpty()) continue
            if (i > 0) {
                container.addView(TextView(this).apply {
                    text = " / "
                    setTextColor(Color.parseColor("#444444"))
                    textSize = 10f
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
            // 뱃지
            container.addView(TextView(this).apply {
                text = label
                textSize = 9f
                setPadding(5, 2, 5, 2)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 3 }
                val bg = android.graphics.drawable.GradientDrawable()
                bg.cornerRadius = 3f
                when (label) {
                    "투" -> { bg.setColor(Color.parseColor("#2A2000")); setTextColor(Color.parseColor("#FFD700")) }
                    "타" -> { bg.setColor(Color.parseColor("#1A1A1A")); bg.setStroke(1, Color.parseColor("#333333")); setTextColor(Color.parseColor("#FFD700")) }
                    "승" -> { bg.setColor(Color.parseColor("#0D3320")); setTextColor(Color.parseColor("#4CD964")) }
                    "패" -> { bg.setColor(Color.parseColor("#3D1515")); setTextColor(Color.parseColor("#FF6B6B")) }
                    "세" -> { bg.setColor(Color.parseColor("#0D2040")); setTextColor(Color.parseColor("#4A9EFF")) }
                    "홀" -> { bg.setColor(Color.parseColor("#1A0A30")); setTextColor(Color.parseColor("#A78FFF")) }
                    "선발" -> { bg.setColor(Color.parseColor("#1A2030")); setTextColor(Color.parseColor("#8A9FBF")) }
                    else  -> { bg.setColor(Color.parseColor("#555555")); setTextColor(Color.WHITE) }
                }
                background = bg
            })
            // 이름
            container.addView(TextView(this).apply {
                text = name
                setTextColor(if (label == "선발") Color.parseColor("#888888") else Color.parseColor("#CCCCCC"))
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }

    private fun loadRanking(away: String, home: String, myTeam: String) {
        val now = System.currentTimeMillis()
        if (cachedRankingList.isNotEmpty() && now - cachedRankingTime < RANKING_TTL) {
            layoutFullRanking.visibility = View.VISIBLE
            renderRanking(cachedRankingList, away, home, myTeam)
            return
        }
        client.newCall(
            Request.Builder().url("$BASE/api/ranking").build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cachedRankingList.isNotEmpty()) {
                    runOnUiThread {
                        layoutFullRanking.visibility = View.VISIBLE
                        renderRanking(cachedRankingList, away, home, myTeam)
                    }
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body    = response.body?.string() ?: return
                val json    = JSONObject(body)
                val ranking = json.getJSONArray("ranking")
                val list = (0 until ranking.length()).map { i ->
                    val r = ranking.getJSONObject(i)
                    mapOf(
                        "rank"   to r.getString("rank"),
                        "team"   to r.getString("team"),
                        "pct"    to r.optString("pct", ""),
                        "win"    to r.getString("win"),
                        "lose"   to r.getString("lose"),
                        "draw"   to r.optString("draw", "0"),
                        "streak" to r.optString("streak", "")
                    )
                }
                cachedRankingList = list
                cachedRankingTime = System.currentTimeMillis()
                runOnUiThread {
                    layoutFullRanking.visibility = View.VISIBLE
                    renderRanking(list, away, home, myTeam)
                }
            }
        })
    }

    /**
     * 양팀 순위 카드의 팀명 칩(TextView)에 팀명 설정 + 팀 컬러를 배경 tint로 적용.
     * - 칩 모양은 activity_main.xml의 @drawable/bg_team_chip (둥근 사각형)
     * - 배경색은 TEAM_COLORS 매핑. 미등록 팀이면 회색 fallback.
     */
    private fun applyTeamChip(tv: TextView, team: String) {
        tv.text = team
        val color = TEAM_COLORS[team] ?: 0xFF555555.toInt()
        tv.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun renderRanking(
        list: List<Map<String, String>>,
        away: String, home: String, myTeam: String
    ) {
        layoutRankingContainer.removeAllViews()
        var awayRank = "-"; var awayRecord = ""
        var homeRank = "-"; var homeRecord = ""

        for (r in list) {
            val team   = r["team"] ?: continue
            val win    = r["win"] ?: "0"
            val lose   = r["lose"] ?: "0"
            val draw   = r["draw"] ?: "0"
            val pct    = r["pct"] ?: ""
            val streak = r["streak"] ?: ""
            val drawNum = draw.toIntOrNull() ?: 0

            val record = if (drawNum > 0) "${win}승 ${lose}패 ${draw}무" else "${win}승 ${lose}패"
            if (team == away) { awayRank = "${r["rank"]}위"; awayRecord = record }
            if (team == home) { homeRank = "${r["rank"]}위"; homeRecord = record }

            val isHighlight = myTeam.isNotEmpty() && team == myTeam

            // 순위 행
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 1 }
                setPadding(if (isHighlight) 3.dp else 0, 4, if (isHighlight) 3.dp else 0, 4)
                if (isHighlight) {
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(Color.parseColor("#14140A"))
                    bg.cornerRadius = 5f
                    background = bg
                }
            }

            val rankColor = "#FFD700"
            val textColor = if (isHighlight) "#FFD700" else "#CCCCCC"
            val subColor  = if (isHighlight) "#FFD700" else "#666666"

            // 모든 컬럼 균등 너비 + 가운데 정렬 (헤더 행과 일치)
            val cellParams = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }

            // 순위
            row.addView(TextView(this).apply {
                text = "${r["rank"]}위"
                setTextColor(Color.parseColor(rankColor))
                textSize = 10f
                gravity = Gravity.CENTER
                if (isHighlight) setTypeface(null, Typeface.BOLD)
                layoutParams = cellParams()
            })
            // 팀명
            row.addView(TextView(this).apply {
                text = team
                setTextColor(Color.parseColor(textColor))
                textSize = 10f
                gravity = Gravity.CENTER
                if (isHighlight) setTypeface(null, Typeface.BOLD)
                layoutParams = cellParams()
            })
            // 승률
            row.addView(TextView(this).apply {
                text = pct
                setTextColor(Color.parseColor(subColor))
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = cellParams()
            })
            // 승
            row.addView(TextView(this).apply {
                text = win
                setTextColor(Color.parseColor(subColor))
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = cellParams()
            })
            // 패
            row.addView(TextView(this).apply {
                text = lose
                setTextColor(Color.parseColor(subColor))
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = cellParams()
            })
            // 무
            row.addView(TextView(this).apply {
                text = if (drawNum > 0) draw else "-"
                setTextColor(Color.parseColor(subColor))
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = cellParams()
            })
            // 연속
            val isWinStreak = streak.contains("승")
            row.addView(TextView(this).apply {
                text = streak
                setTextColor(Color.parseColor(if (isWinStreak) "#4CD964" else "#FF6B6B"))
                textSize = 10f
                gravity = Gravity.CENTER
                if (isHighlight) setTypeface(null, Typeface.BOLD)
                layoutParams = cellParams()
            })

            layoutRankingContainer.addView(row)

            // 행 구분선
            if (list.last()["team"] != team) {
                layoutRankingContainer.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#1A1A2E"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                })
            }
        }

        cachedAwayRank   = awayRank
        cachedHomeRank   = homeRank
        cachedAwayRecord = awayRecord
        cachedHomeRecord = homeRecord
        tvAwayRank.text   = awayRank
        tvHomeRank.text   = homeRank
        tvAwayRecord.text = awayRecord
        tvHomeRecord.text = homeRecord
    }

    private fun loadRecentGames(away: String, home: String) {
        fun loadTeam(team: String, row1: LinearLayout, row2: LinearLayout, isAway: Boolean) {
            client.newCall(
                Request.Builder().url("$BASE/api/recent?team=$team").build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    val body   = response.body?.string() ?: return
                    val json   = JSONObject(body)
                    val recent = json.getJSONArray("recent")
                    val list   = (0 until recent.length()).map { recent.getString(it) }
                    runOnUiThread {
                        if (isAway) cachedAwayRecent = list else cachedHomeRecent = list
                        updateRecentView(row1, row2, list)
                    }
                }
            })
        }
        loadTeam(away, tvAwayRecentRow1, tvAwayRecentRow2, true)
        loadTeam(home, tvHomeRecentRow1, tvHomeRecentRow2, false)
    }

    private fun updateRecentView(row1: LinearLayout, row2: LinearLayout, results: List<String>) {
        row1.removeAllViews()
        row2.removeAllViews()
        if (results.isEmpty()) {
            row1.addView(TextView(this).apply {
                text = "-"
                setTextColor(Color.parseColor("#666666"))
                textSize = 13f
                gravity = Gravity.CENTER
            })
            return
        }
        results.forEachIndexed { idx, result ->
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = 5f
            when (result) {
                "승" -> { bg.setColor(Color.parseColor("#0D3320")) }
                "패" -> { bg.setColor(Color.parseColor("#3D1515")) }
                else -> { bg.setColor(Color.parseColor("#252525")) }
            }
            val txtColor = when (result) { "승" -> "#4CD964"; "패" -> "#FF6B6B"; else -> "#888888" }
            val badge = TextView(this).apply {
                text = result
                setTextColor(Color.parseColor(txtColor))
                background = bg
                textSize = 11f
                setPadding(6, 3, 6, 3)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 2 }
            }
            if (idx < 5) row1.addView(badge) else row2.addView(badge)
        }
    }

    /**
     * 프로세스 재시작(위젯 터치, 시스템 종료 후 복귀) 시 companion object 캐시를 SharedPreferences에서 복원.
     * API 호출 없이 즉시 이전 화면을 재구성할 수 있도록 함.
     */
    private fun restoreScheduleFromPrefs() {
        val prefs    = getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA)
            .format(java.util.Calendar.getInstance().time)

        val pDateKey = prefs.getString("app_sched_date_key", "") ?: ""
        val pAway    = prefs.getString("app_sched_away", "") ?: ""
        if (pDateKey != todayStr || pAway.isEmpty()) return   // 날짜 불일치 or 데이터 없음

        cachedAway      = pAway
        cachedHome      = prefs.getString("app_sched_home", "") ?: ""
        cachedTeam      = prefs.getString("app_sched_team", "") ?: ""
        cachedDate      = prefs.getString("app_sched_date_display", "") ?: ""
        cachedTime      = prefs.getString("app_sched_time", "") ?: ""
        cachedStadium   = prefs.getString("app_sched_stadium", "") ?: ""
        cachedBroadcast = prefs.getString("app_sched_broadcast", "") ?: ""
        lastLoadTime    = prefs.getLong("app_sched_last_load", 0L)

        // 스코어 복원 (status 1·2 모두, inning 포함)
        val pStatus = prefs.getString("app_status", "") ?: ""
        val pAScore = prefs.getString("app_away_score", "") ?: ""
        val pHScore = prefs.getString("app_home_score", "") ?: ""
        val pInning = prefs.getString("app_inning", "") ?: ""
        val pSDate  = prefs.getString("app_date", "") ?: ""
        if ((pStatus == "1" || pStatus == "2") && pAScore.isNotEmpty() && pSDate == todayStr) {
            cachedStatus    = pStatus
            cachedAwayScore = pAScore
            cachedHomeScore = pHScore
            cachedInning    = if (pStatus == "2") "경기종료" else pInning
        } else if (pStatus == "3" && pSDate == todayStr) {
            // 우천취소 상태 복원 (점수 없음)
            cachedStatus    = "3"
            cachedAwayScore = ""
            cachedHomeScore = ""
            cachedInning    = pInning
        }

        // ✅ 투수/타자 캐시 복원 (앱 재진입 시 사라짐 버그 수정)
        cachedAwayPitchers = prefs.getString("app_away_pitchers", "") ?: ""
        cachedHomePitchers = prefs.getString("app_home_pitchers", "") ?: ""
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}