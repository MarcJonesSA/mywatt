package com.mywatt.app.history

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.mywatt.app.R
import com.mywatt.app.data.history.HistoryDatabase
import com.mywatt.app.data.history.SessionWithReps
import com.mywatt.app.databinding.ActivityHistoryDetailBinding
import com.mywatt.app.ui.ResultsTable
import com.mywatt.app.ui.padForSystemBars
import kotlinx.coroutines.launch

/** One past session: when, at what FTP and recovery setting, its verdict and its results table. */
class HistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        lifecycleScope.launch {
            val entry = HistoryDatabase.get(this@HistoryDetailActivity).dao().sessionWithReps(id)
            if (entry == null) finish() else render(entry)
        }
    }

    private fun render(entry: SessionWithReps) {
        val session = entry.session
        binding.detailTitle.text = session.sessionName
        binding.detailMeta.text = buildList {
            add(HistoryActivity.formatDate(session.startedAtMs))
            session.ftpWatts?.let { add(getString(R.string.ftp_button, it)) }
            add(getString(R.string.results_recovery_setting, entry.recoverySetting.label))
            add(getString(if (session.completed) R.string.history_completed else R.string.history_ended_early))
        }.joinToString(" · ")
        binding.detailVerdict.text = session.verdict
        ResultsTable.render(binding.resultsTable, entry.repResults)
    }

    companion object {
        const val EXTRA_ID = "session_record_id"
    }
}
