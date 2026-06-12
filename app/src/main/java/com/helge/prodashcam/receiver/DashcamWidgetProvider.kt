package com.helge.prodashcam.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.helge.prodashcam.R
import com.helge.prodashcam.service.RecordingService

class DashcamWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.dashcam_widget)

            // Start action
            val startIntent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_START }
            val startPendingIntent = PendingIntent.getService(context, 10, startIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_btn_start, startPendingIntent)

            // Stop action
            val stopIntent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
            val stopPendingIntent = PendingIntent.getService(context, 11, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_btn_stop, stopPendingIntent)

            // Lock action
            val lockIntent = Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_LOCK }
            val lockPendingIntent = PendingIntent.getService(context, 12, lockIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_btn_lock, lockPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
