package com.zeroclaw.zero.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zeroclaw.zero.R
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.ui.MainActivity

class ZeroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        fun updateWidget(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ZeroWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                updateAppWidget(context, manager, id)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_zero)

            // Tap orb → launch voice mode
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_VOICE_ACTIVATE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetOrb, pendingIntent)

            // Show last response preview
            val preview = ZeroApp.instance.prefs.lastResponse
            if (!preview.isNullOrBlank()) {
                views.setTextViewText(R.id.widgetStatus, preview.take(80))
            } else {
                views.setTextViewText(
                    R.id.widgetStatus,
                    context.getString(R.string.widget_tap)
                )
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
