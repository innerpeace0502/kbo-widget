package com.example.kbowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * 경기 상황(이닝 + 주자 다이아몬드 + B·S·O 카운트)을 한 장의 투명 배경 비트맵으로 그린다.
 * 앱(MainActivity ImageView)과 큰 위젯(RemoteViews ImageView)이 공유해 룩을 일치시킨다.
 * 배경(둥근 박스)은 각 컨테이너가 XML로 제공하고, 여기선 이닝+다이아몬드+점만 그린다.
 *
 * 경기장명을 빼고 확보한 높이만큼 이전보다 약 2배 크게 그린다.
 * 이닝은 왼쪽에 화살표+숫자로 표시 — 초(▲)=빨강 / 말(▼)=파랑, 숫자도 화살표 색으로 통일.
 *
 * bases: [1루, 2루, 3루] 주자 여부.
 */
object SituationDrawer {
    private const val W = 460
    private const val H = 150

    private val GOLD       = Color.parseColor("#FFD700")
    private val EMPTY_FILL = Color.parseColor("#16242F")
    private val EMPTY_STK  = Color.parseColor("#3A4A5A")
    private val HOME_GRAY  = Color.parseColor("#2A3A4A")
    private val LABEL      = Color.parseColor("#8A9FBF")
    private val DOT_OFF    = Color.parseColor("#26313C")
    private val ON_B       = Color.parseColor("#4CD964")
    private val ON_S       = Color.parseColor("#FFD700")
    private val ON_O       = Color.parseColor("#FF6B6B")
    private val INN_TOP    = Color.parseColor("#FF6B6B")  // 초(▲)
    private val INN_BOT    = Color.parseColor("#4A9EFF")  // 말(▼) — 어두운 배경(위젯·앱)용
    // 밝은 배경의 알림에서는 하늘색이 묻혀서 진한 남색 사용 (알림 바 전용)
    private val INN_BOT_NOTI = Color.parseColor("#1B3A6B")
    private val BADGE_TXT  = Color.parseColor("#1A1A2E")  // 채널번호 뱃지 글자(금색 배경 위)
    private val DIV        = Color.parseColor("#2E2E44")  // 한 줄 바 구획 세로 구분선

    fun makeBitmap(inning: String, bases: BooleanArray, balls: Int, strikes: Int, outs: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── 이닝 (좌측): 화살표 + 숫자 ──
        drawInning(c, p, inning, 64f, 72f)

        // ── 다이아몬드 (가운데) — 2루 위, 1루 오른쪽, 3루 왼쪽 ──
        // ⚠️ 베이스 간격(off)이 반지름(r)보다 충분히 커야 서로 안 겹친다.
        // (이전 r=24/off=34는 겹쳐서 다이아몬드가 덩어리로 뭉쳐 보였음)
        val cx = 200f; val cy = 72f; val r = 19f; val off = 44f
        drawBase(c, p, cx, cy - off, r, bases.getOrElse(1) { false })  // 2루(top)
        drawBase(c, p, cx + off, cy, r, bases.getOrElse(0) { false })  // 1루(right)
        drawBase(c, p, cx - off, cy, r, bases.getOrElse(2) { false })  // 3루(left)
        // 홈플레이트 마커(작은 회색 오각형) — 다이아몬드 아래 꼭짓점
        p.style = Paint.Style.FILL; p.color = HOME_GRAY
        val hp = Path().apply {
            moveTo(cx, cy + off - 8f); lineTo(cx + 9f, cy + off - 1f); lineTo(cx + 6f, cy + off + 8f)
            lineTo(cx - 6f, cy + off + 8f); lineTo(cx - 9f, cy + off - 1f); close()
        }
        c.drawPath(hp, p)

        // ── B/S/O (우측) ──
        val labelX = 300f; val dotX0 = 348f; val gap = 42f; val dotR = 13f
        drawCountRow(c, p, "B", labelX, 42f,  dotX0, gap, dotR, 3, balls,   ON_B)
        drawCountRow(c, p, "S", labelX, 76f,  dotX0, gap, dotR, 2, strikes, ON_S)
        drawCountRow(c, p, "O", labelX, 110f, dotX0, gap, dotR, 2, outs,    ON_O)
        return bmp
    }

    /**
     * 큰 위젯 전용 한 줄 상황 바: [이닝] | [●LIVE·채널명·번호] | [베이스] | [B·S·O].
     * 상황 카드 + LIVE 행 + 채널 행을 한 줄로 합쳐 위젯 세로 높이를 크게 줄인다(잘림 방지).
     * 채널명/번호는 fetchChannel이 prefs에 캐시한 값을 전달받는다(없으면 LIVE만 표시).
     * 캔버스 700x130(≈5.4:1) — 위젯 iv_situation도 같은 비율의 와이드 바.
     */
    /**
     * 고정 알림용 한 줄 바 — [이닝 ▲N | 주자 다이아몬드 | B·S·O] 3요소만.
     * 위젯 바(makeWidgetBar)와 달리 LIVE/채널 칸이 없고, 요소를 넓은 간격으로
     * 중앙 배치한다 (알림 확장 뷰 fitCenter 기준).
     */
    fun makeNotificationBar(inning: String, bases: BooleanArray,
                            balls: Int, strikes: Int, outs: Int): Bitmap {
        val w = 560; val h = 130
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.DEFAULT_BOLD
        val cy = 65f

        // 1) 이닝 ▲N (초=▲빨강 / 말=▼진남색 — 밝은 알림 배경 가독성)
        val m = Regex("(\\d+)").find(inning)
        if (m != null) {
            val isBot = inning.contains("말")
            val icol = if (isBot) INN_BOT_NOTI else INN_TOP
            val tcx = 70f
            p.style = Paint.Style.FILL; p.color = icol
            val tri = Path().apply {
                if (isBot) { moveTo(tcx - 13f, cy - 9f); lineTo(tcx + 13f, cy - 9f); lineTo(tcx, cy + 13f) }
                else { moveTo(tcx - 13f, cy + 9f); lineTo(tcx + 13f, cy + 9f); lineTo(tcx, cy - 13f) }
                close()
            }
            c.drawPath(tri, p)
            p.textSize = 62f; p.textAlign = Paint.Align.LEFT
            c.drawText(m.groupValues[1], tcx + 16f, cy + 22f, p)
        }

        // 2) 베이스 다이아몬드 (중앙)
        val bcx = 280f; val r = 20f; val off = 42f
        drawBase(c, p, bcx, cy - off, r, bases.getOrElse(1) { false })  // 2루(top)
        drawBase(c, p, bcx + off, cy, r, bases.getOrElse(0) { false })  // 1루(right)
        drawBase(c, p, bcx - off, cy, r, bases.getOrElse(2) { false })  // 3루(left)
        p.style = Paint.Style.FILL; p.color = HOME_GRAY
        val hp = Path().apply {
            moveTo(bcx, cy + off - 7f); lineTo(bcx + 8f, cy + off); lineTo(bcx + 6f, cy + off + 9f)
            lineTo(bcx - 6f, cy + off + 9f); lineTo(bcx - 8f, cy + off); close()
        }
        c.drawPath(hp, p)

        // 3) B/S/O (우측)
        drawCountRow(c, p, "B", 415f, 35f, 455f, 34f, 11f, 3, balls,   ON_B)
        drawCountRow(c, p, "S", 415f, 65f, 455f, 34f, 11f, 2, strikes, ON_S)
        drawCountRow(c, p, "O", 415f, 95f, 455f, 34f, 11f, 2, outs,    ON_O)
        return bmp
    }

    fun makeWidgetBar(inning: String, chName: String, chNum: String,
                      bases: BooleanArray, balls: Int, strikes: Int, outs: Int): Bitmap {
        val w = 700; val h = 130
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.DEFAULT_BOLD
        val cy = 65f

        // 1) 이닝 ▼N (초=▲빨강 / 말=▼파랑)
        val m = Regex("(\\d+)").find(inning)
        if (m != null) {
            val isBot = inning.contains("말")
            val icol = if (isBot) INN_BOT else INN_TOP
            val tcx = 40f
            p.style = Paint.Style.FILL; p.color = icol
            val tri = Path().apply {
                if (isBot) { moveTo(tcx - 13f, cy - 9f); lineTo(tcx + 13f, cy - 9f); lineTo(tcx, cy + 13f) }
                else { moveTo(tcx - 13f, cy + 9f); lineTo(tcx + 13f, cy + 9f); lineTo(tcx, cy - 13f) }
                close()
            }
            c.drawPath(tri, p)
            p.textSize = 62f; p.textAlign = Paint.Align.LEFT
            c.drawText(m.groupValues[1], tcx + 16f, cy + 22f, p)
        }
        vDiv(c, p, 150f, h)

        // 2) ● LIVE / 채널명 [번호뱃지]
        p.style = Paint.Style.FILL; p.color = ON_O
        c.drawCircle(181f, 42f, 6f, p)
        p.textSize = 24f; p.textAlign = Paint.Align.LEFT
        c.drawText("LIVE", 193f, 50f, p)
        if (chName.isNotEmpty()) {
            p.color = GOLD; p.textSize = 28f
            c.drawText(chName, 175f, 100f, p)
            if (chNum.isNotEmpty()) {
                val sw = p.measureText(chName)
                val bx = 175f + sw + 8f
                p.textSize = 22f
                val numW = p.measureText(chNum) + 16f
                p.style = Paint.Style.FILL; p.color = GOLD
                c.drawRoundRect(bx, 80f, bx + numW, 106f, 5f, 5f, p)
                p.color = BADGE_TXT
                c.drawText(chNum, bx + 8f, 101f, p)
            }
        }
        vDiv(c, p, 375f, h)

        // 3) 베이스 다이아몬드
        val bcx = 445f; val r = 20f; val off = 42f
        drawBase(c, p, bcx, cy - off, r, bases.getOrElse(1) { false })  // 2루(top)
        drawBase(c, p, bcx + off, cy, r, bases.getOrElse(0) { false })  // 1루(right)
        drawBase(c, p, bcx - off, cy, r, bases.getOrElse(2) { false })  // 3루(left)
        p.style = Paint.Style.FILL; p.color = HOME_GRAY
        val hp = Path().apply {
            moveTo(bcx, cy + off - 7f); lineTo(bcx + 8f, cy + off); lineTo(bcx + 6f, cy + off + 9f)
            lineTo(bcx - 6f, cy + off + 9f); lineTo(bcx - 8f, cy + off); close()
        }
        c.drawPath(hp, p)
        vDiv(c, p, 540f, h)

        // 4) B/S/O
        drawCountRow(c, p, "B", 565f, 35f, 605f, 34f, 11f, 3, balls,   ON_B)
        drawCountRow(c, p, "S", 565f, 65f, 605f, 34f, 11f, 2, strikes, ON_S)
        drawCountRow(c, p, "O", 565f, 95f, 605f, 34f, 11f, 2, outs,    ON_O)
        return bmp
    }

    private fun vDiv(c: Canvas, p: Paint, x: Float, h: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = DIV
        c.drawLine(x, 25f, x, h - 25f, p)
    }

    /** 이닝 문자열("5회초"/"5회말") → 화살표+숫자. 초=▲빨강 / 말=▼파랑 (숫자도 화살표 색). */
    private fun drawInning(c: Canvas, p: Paint, inning: String, cx: Float, cy: Float) {
        val num = Regex("(\\d+)").find(inning)?.value ?: return
        val isBot = inning.contains("말")
        val color = if (isBot) INN_BOT else INN_TOP

        // 화살표(삼각형)
        p.style = Paint.Style.FILL
        p.color = color
        val triCx = cx - 24f
        val triR  = 12f
        val tri = Path().apply {
            if (isBot) {
                moveTo(triCx - triR, cy - triR + 2f)
                lineTo(triCx + triR, cy - triR + 2f)
                lineTo(triCx, cy + triR + 2f)
            } else {
                moveTo(triCx - triR, cy + triR - 2f)
                lineTo(triCx + triR, cy + triR - 2f)
                lineTo(triCx, cy - triR - 2f)
            }
            close()
        }
        c.drawPath(tri, p)

        // 숫자
        p.color = color
        p.textSize = 58f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        c.drawText(num, cx - 8f, cy + 21f, p)
    }

    private fun drawBase(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, on: Boolean) {
        val path = Path().apply {
            moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
        }
        p.style = Paint.Style.FILL
        p.color = if (on) GOLD else EMPTY_FILL
        c.drawPath(path, p)
        if (!on) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = EMPTY_STK
            c.drawPath(path, p)
        }
    }

    private fun drawCountRow(
        c: Canvas, p: Paint, label: String, labelX: Float, y: Float,
        dotX0: Float, gap: Float, r: Float, total: Int, lit: Int, onColor: Int
    ) {
        p.style = Paint.Style.FILL
        p.color = LABEL
        p.textSize = 30f
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(label, labelX, y + 11f, p)
        for (i in 0 until total) {
            p.color = if (i < lit) onColor else DOT_OFF
            c.drawCircle(dotX0 + i * gap, y, r, p)
        }
    }
}
