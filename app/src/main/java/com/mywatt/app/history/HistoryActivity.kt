package com.mywatt.app.history

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.mywatt.app.R
import com.mywatt.app.data.history.HistoryDatabase
import com.mywatt.app.data.history.SessionRecord
import com.mywatt.app.databinding.ActivityHistoryBinding
import com.mywatt.app.ui.padForSystemBars
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Past sessions, newest first. Tap one for its results table. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val sessions = HistoryDatabase.get(this@HistoryActivity).dao().sessions()
            render(sessions)
        }
    }

    private fun render(sessions: List<SessionRecord>) {
        val list = binding.historyList
        list.removeAllViews()
        binding.emptyText.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        sessions.forEach { record ->
            val row = layoutInflater.inflate(R.layout.item_list_row, list, false)
            row.findViewById<TextView>(R.id.rowTitle).text =
                getString(R.string.history_row_title, record.sessionName, formatDate(record.startedAtMs))
            row.findViewById<TextView>(R.id.rowSubtitle).text = record.verdict
            row.setOnClickListener {
                startActivity(
                    Intent(this, HistoryDetailActivity::class.java)
                        .putExtra(HistoryDetailActivity.EXTRA_ID, record.id)
                )
            }
            list.addView(row)
        }
    }

    companion object {
        /** e.g. "Wed 19 Nov, 18:04". */
        fun formatDate(ms: Long): String =
            SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK).format(Date(ms))
    }
}
