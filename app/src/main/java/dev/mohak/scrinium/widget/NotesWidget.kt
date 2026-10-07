package dev.mohak.scrinium.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import dev.mohak.scrinium.MainActivity
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ScriniumApplication
import dev.mohak.scrinium.data.local.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home screen widget: the most recently edited notes plus a new-note button.
 * It only reads Room, so it never touches the network. AppContainer redraws
 * it whenever the notes table changes; the system asks for a draw only when
 * a widget is placed or the app is updated (no periodic updates).
 */
object NotesWidget {
    private const val MAX_ROWS = 8

    fun render(context: Context, notes: List<NoteEntity>) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NotesWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_notes)
        views.setOnClickPendingIntent(R.id.widget_title, open(context, Uri.parse("scrinium:home")))
        views.setOnClickPendingIntent(
            R.id.widget_new,
            open(context, Uri.parse("scrinium:new")) { putExtra(MainActivity.EXTRA_NEW_NOTE, true) }
        )
        views.removeAllViews(R.id.widget_list)
        val recent = recentNotes(notes, MAX_ROWS)
        for (note in recent) {
            val row = RemoteViews(context.packageName, R.layout.widget_note_row)
            row.setTextViewText(R.id.row_title, note.path.substringAfterLast('/').removeSuffix(".md"))
            val folder = note.path.substringBeforeLast('/', "")
            row.setTextViewText(R.id.row_folder, folder)
            row.setViewVisibility(R.id.row_folder, if (folder.isEmpty()) View.GONE else View.VISIBLE)
            row.setOnClickPendingIntent(
                R.id.row,
                open(context, Uri.fromParts("note", note.path, null)) { putExtra(MainActivity.EXTRA_NOTE_PATH, note.path) }
            )
            views.addView(R.id.widget_list, row)
        }
        views.setViewVisibility(R.id.widget_empty, if (recent.isEmpty()) View.VISIBLE else View.GONE)
        manager.updateAppWidget(ids, views)
    }

    // Distinct data per target keeps each row's PendingIntent separate.
    private fun open(context: Context, data: Uri, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setData(data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** Live notes, last edited first; a local edit counts as newer than the server time. */
fun recentNotes(notes: List<NoteEntity>, limit: Int): List<NoteEntity> = notes
    .filter { !it.isDeleted }
    .sortedByDescending { maxOf(it.localModifiedAt ?: 0L, it.remoteUpdatedAt) }
    .take(limit)

class NotesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val dao = (context.applicationContext as ScriniumApplication).container.database.noteDao()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotesWidget.render(context, dao.getAll())
            } finally {
                pending.finish()
            }
        }
    }
}
