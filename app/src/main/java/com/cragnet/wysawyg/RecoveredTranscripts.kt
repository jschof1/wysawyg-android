package com.cragnet.wysawyg

import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Lists transcripts saved when insertion failed, with Copy, Discard and storage recovery. */
object RecoveredTranscripts {
    private const val POLICY = "Saved transcripts expire after 24 hours. Up to 5 are kept; new recording pauses when full. " +
        "To insert, select a non-password text box with your keyboard open, then tap Insert here on the floating card. Pause keeps saved text."

    fun show(activity: AppCompatActivity, onChanged: () -> Unit) {
        val store = Recoveries.get(activity)
        val snapshot = store.snapshot()
        fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        if (snapshot.items.isEmpty()) {
            val dialog = MaterialAlertDialogBuilder(activity).setTitle("Recovered transcripts").setMessage(
                when {
                    snapshot.unreadable -> "Recovery file is unreadable. Discard unreadable data to resume. $POLICY"
                    snapshot.writeFailed -> "Recovery storage could not be updated. Retry saving before recording again. $POLICY"
                    else -> "Nothing to recover. $POLICY"
                }
            ).setPositiveButton("Close", null)
            if (snapshot.unreadable) dialog.setNegativeButton("Discard unreadable data") { _, _ ->
                if (!store.discardUnreadable()) toast("Could not clear recovery storage.")
                onChanged()
            }
            if (snapshot.writeFailed && !snapshot.unreadable) dialog.setNeutralButton("Retry saving") { _, _ ->
                if (!store.retrySave()) toast("Could not update recovery storage.")
                onChanged()
            }
            dialog.show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Recovered transcripts")
            .setItems(snapshot.items.mapIndexed { i, item ->
                "${i + 1}. ${if (item.id in snapshot.unsavedIds) "Not saved: " else ""}${item.text.take(60)}"
            }.toTypedArray()) { _, index ->
                val item = snapshot.items[index]
                val padding = (20 * activity.resources.displayMetrics.density).toInt()
                val text = TextView(activity).apply {
                    text = item.text
                    textSize = 16f
                    setTextIsSelectable(true)
                    setPadding(padding, padding, padding, padding)
                }
                val title = if (item.id in snapshot.unsavedIds) "Not saved; memory only" else "Saved for 24 hours"
                MaterialAlertDialogBuilder(activity).setTitle(title).setView(ScrollView(activity).apply { addView(text) })
                    .setPositiveButton("Copy") { _, _ ->
                        if (!Recoveries.copy(activity, item)) toast("Could not copy the full transcript. Text retained.")
                    }.setNegativeButton("Discard") { _, _ ->
                        if (!store.remove(item.id)) toast("Could not save removal. Transcript retained.")
                        onChanged()
                    }.setNeutralButton("Close", null).show()
            }.setNegativeButton("Close", null)
        if (snapshot.writeFailed && !snapshot.unreadable) dialog.setPositiveButton("Retry saving") { _, _ ->
            if (!store.retrySave()) toast("Could not save recovery text. Copy before closing the app.")
            onChanged()
            show(activity, onChanged)
        }
        dialog.show()
    }
}
