package com.wxmsglog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 待办提醒的通知。独立渠道，用户可以单独关掉它而不影响应用本身。 */
object TodoNotify {

    private const val CHANNEL_ID = "todo_remind"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_todo),
            NotificationManager.IMPORTANCE_HIGH,
        )
        ch.description = context.getString(R.string.channel_todo_desc)
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 400, 200, 400)
        runCatching { nm.createNotificationChannel(ch) }
    }

    /**
     * @param round 强提醒第几轮。0 是正常提醒，后面的是补刀，
     *              标题上会带「第 N 次提醒」让用户知道这不是新的一条。
     */
    fun show(context: Context, item: Todo, round: Int = 0) {
        ensureChannel(context)
        // Android 13 起通知要用户授权，没授权直接发会被静默丢掉，先自己判一下
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val open = Intent(context, TodoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            100,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 「完成」：直接从通知栏勾掉，不用点进 App
        val done = PendingIntent.getBroadcast(
            context,
            200,
            Intent(context, TodoAlarmReceiver::class.java).apply {
                action = TodoAlarmReceiver.ACTION_DONE
                putExtra(TodoAlarmReceiver.EXTRA_ID, item.id)
                data = android.net.Uri.parse("wxmsglog://todo/done/${item.id}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 「稍后」：推 10 分钟
        val later = PendingIntent.getBroadcast(
            context,
            300,
            Intent(context, TodoAlarmReceiver::class.java).apply {
                action = TodoAlarmReceiver.ACTION_SNOOZE
                putExtra(TodoAlarmReceiver.EXTRA_ID, item.id)
                data = android.net.Uri.parse("wxmsglog://todo/snooze/${item.id}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (round > 0) {
            context.getString(R.string.todo_notify_title_again, round + 1)
        } else {
            context.getString(R.string.todo_notify_title)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_todo)
            .setContentTitle(title)
            .setContentText(item.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, context.getString(R.string.todo_notify_done), done)
            .addAction(0, context.getString(R.string.todo_notify_later), later)

        // 强提醒才把锁屏直接点亮。普通提醒就安静待在通知栏，别一惊一乍
        if (round > 0 || Prefs.strongAlarm(context)) {
            builder.setFullScreenIntent(pi, true)
        }

        // 每条待办一个通知 id，同时有几条要办时才不会互相顶掉
        runCatching { nm.notify(item.id.toInt(), builder.build()) }
    }

    fun dismiss(context: Context, id: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(id.toInt()) }
    }
}
