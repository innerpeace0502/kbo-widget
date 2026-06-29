package com.example.kbowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * 경기 상황(주자 다이아몬드 + B·S·O 카운트)을 한 장의 투명 배경 비트맵으로 그린다.
 * 앱(MainActivity ImageView)과 큰 위젯(RemoteViews ImageView)이 공유해 룩을 일치시킨다.
 * 배경(둥근 박스)은 각 컨테이너가 XML로 제공하고, 여기선 다이아몬드+점만 그린다.
 *
 * bases: [1루, 2루, 3루] 주자 여부.
 */
object SituationDrawer {
    private const val W = 300
    private const val H = 110

    private val GOLD     = Color.parseColor("#FFD700")
    private val EMPTY_FILL = Color.parseColor("#16242F")
    private val EMPTY_STK  = Color.parseColor("#3A4A5A")
    private val HOME_GRAY  = Color.parseColor("#2A3A4A")
    private val LABEL      = Color.parseColor("#8A9FBF")
    private val DOT_OFF    = Color.parseColor("#26313C")
    private val ON_B       = Color.parseColor("#4CD964")
    private val ON_S       = Color.parseColor("#FFD700")
    private val ON_O       = Color.parseColor("#FF6B6B")

    fun makeBitmap(bases: BooleanArray, balls: Int, strikes: Int, outs: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── 다이아몬드 (좌측) — 2루 위, 1루 오른쪽, 3루 왼쪽 ──
        val cx = 52f; val cy = 55f; val r = 15f
        drawBase(c, p, cx, cy - 22f, r, bases.getOrElse(1) { false })  // 2루(top)
        drawBase(c, p, cx + 22f, cy, r, bases.getOrElse(0) { false })  // 1루(right)
        drawBase(c, p, cx - 22f, cy, r, bases.getOrElse(2) { false })  // 3루(left)
        // 홈플레이트 마커(작은 회색 오각형)
        p.style = Paint.Style.FILL; p.color = HOME_GRAY
        val hp = Path().apply {
            moveTo(cx, cy + 17f); lineTo(cx + 6f, cy + 23f); lineTo(cx + 4f, cy + 30f)
            lineTo(cx - 4f, cy + 30f); lineTo(cx - 6f, cy + 23f); close()
        }
        c.drawPath(hp, p)

        // ── B/S/O (우측) ──
        val labelX = 120f; val dotX0 = 146f; val gap = 26f; val dotR = 8f
        drawCountRow(c, p, "B", labelX, 30f, dotX0, gap, dotR, 3, balls, ON_B)
        drawCountRow(c, p, "S", labelX, 60f, dotX0, gap, dotR, 2, strikes, ON_S)
        drawCountRow(c, p, "O", labelX, 90f, dotX0, gap, dotR, 2, outs, ON_O)
        return bmp
    }

    private fun drawBase(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, on: Boolean) {
        val path = Path().apply {
            moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
        }
        p.style = Paint.Style.FILL
        p.color = if (on) GOLD else EMPTY_FILL
        c.drawPath(path, p)
        if (!on) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = EMPTY_STK
            c.drawPath(path, p)
        }
    }

    private fun drawCountRow(
        c: Canvas, p: Paint, label: String, labelX: Float, y: Float,
        dotX0: Float, gap: Float, r: Float, total: Int, lit: Int, onColor: Int
    ) {
        p.style = Paint.Style.FILL
        p.color = LABEL
        p.textSize = 20f
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(label, labelX, y + 7f, p)
        for (i in 0 until total) {
            p.color = if (i < lit) onColor else DOT_OFF
            c.drawCircle(dotX0 + i * gap, y, r, p)
        }
    }
}
