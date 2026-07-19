package com.example.kbowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 라이브 고정 알림 자동 기동/재소환 리시버.
 *
 * - (기본) 경기 시작 시각의 정확 알람 → startIfEnabled: 정확 알람 트리거는
 *   Android 12+ 백그라운드 FGS 시작 제한의 공식 예외라 서비스 기동 가능.
 * - ACTION_REPOST: 사용자가 알림을 지웠을 때(deleteIntent) 즉시 재표시.
 *   서비스가 살아있으면 startService가 허용되고(onStartCommand가 재게시),
 *   이미 죽었으면 예외를 무시 (다음 자동 기동 경로가 처리).
 */
class LiveNotiAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LiveNotificationService.ACTION_REPOST) {
            try {
                context.startService(
                    Intent(context, LiveNotificationService::class.java)
                        .setAction(LiveNotificationService.ACTION_REPOST))
            } catch (_: Exception) {}
        } else {
            LiveNotificationService.startIfEnabled(context)
        }
    }
}
