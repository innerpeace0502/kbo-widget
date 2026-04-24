package com.example.kbowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("kbo_prefs", Context.MODE_PRIVATE)

        val spinnerTeam = findViewById<Spinner>(R.id.spinner_team)
        val spinnerIptv = findViewById<Spinner>(R.id.spinner_iptv)
        val btnSave     = findViewById<Button>(R.id.btn_save)
        val tvStatus    = findViewById<TextView>(R.id.tv_status)

        // 팀 목록
        val teams = listOf("전체", "LG", "KT", "SSG", "NC", "두산", "KIA", "롯데", "삼성", "한화", "키움")
        spinnerTeam.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, teams)

        // IPTV 목록
        val iptvList = listOf("선택안함", "olleh(KT)", "genie(LGU+)", "btv(SKT)")
        spinnerIptv.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, iptvList)

        // 저장된 설정 불러오기
        val savedTeam = prefs.getString("team", "전체") ?: "전체"
        val savedIptv = prefs.getString("iptv", "선택안함") ?: "선택안함"
        spinnerTeam.setSelection(teams.indexOf(savedTeam))
        spinnerIptv.setSelection(iptvList.indexOf(savedIptv))

        btnSave.setOnClickListener {
            val selectedTeam = spinnerTeam.selectedItem.toString()
            val selectedIptv = when (spinnerIptv.selectedItem.toString()) {
                "olleh(KT)"  -> "olleh"
                "genie(LGU+)" -> "genie"
                "btv(SKT)"   -> "btv"
                else          -> ""
            }

            // 설정 저장
            prefs.edit()
                .putString("team", selectedTeam)
                .putString("iptv", selectedIptv)
                .apply()

            // 위젯 즉시 업데이트
            val manager = AppWidgetManager.getInstance(this)
            val ids = manager.getAppWidgetIds(ComponentName(this, KboWidgetProvider::class.java))
            val intent = android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(intent)

            tvStatus.text = "✅ 저장완료! 팀: $selectedTeam / IPTV: ${spinnerIptv.selectedItem}"
            Toast.makeText(this, "설정이 저장됐어요!", Toast.LENGTH_SHORT).show()
        }
    }
}