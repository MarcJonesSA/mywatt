package com.mywatt.app.library

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mywatt.app.ui.SessionProfileView
import com.mywatt.app.R
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.databinding.ActivityLibraryBinding
import com.mywatt.app.engine.Library
import com.mywatt.app.engine.LibrarySession
import com.mywatt.app.engine.Ramp
import com.mywatt.app.engine.Region
import com.mywatt.app.engine.Rep
import com.mywatt.app.engine.RepKind
import com.mywatt.app.session.ActiveSession
import com.mywatt.app.session.SessionActivity
import com.mywatt.app.ui.padForSystemBars
import com.mywatt.app.ui.showSettingsDialog

/**
 * The session library: every session grouped by region, one row each with its name,
 * duration and why. Tapping a row asks to confirm with the FTP the targets will be worked
 * out from, then starts it. If FTP has never been set, the settings dialog comes first.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var settings: RiderSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        settings = RiderSettings(this)

        binding.ftpButton.setOnClickListener { showSettingsDialog(this, settings) { renderFtp() } }
        binding.closeButton.setOnClickListener { finish() }
        renderFtp()
        buildList()

        if (!settings.isFtpSet && savedInstanceState == null) askForFtpFirst()
    }

    override fun onResume() {
        super.onResume()
        // FTP can change in the profile screen, opened from settings.
        renderFtp()
    }

    private fun renderFtp() {
        binding.ftpButton.text = getString(R.string.ftp_button, settings.ftpWatts)
    }

    /** Every target is a share of FTP, so a rider who has never set one is asked first. */
    private fun askForFtpFirst() {
        showSettingsDialog(
            this,
            settings,
            title = getString(R.string.library_ftp_first_title),
            message = getString(R.string.library_ftp_first_message),
            onCancelled = { if (!settings.isFtpSet) finish() },
        ) { renderFtp() }
    }

    private fun buildList() {
        val list = binding.sessionList
        list.removeAllViews()
        Region.entries.forEach { region ->
            val sessions = Library.sessions.filter { it.region == region }
            if (sessions.isEmpty()) return@forEach
            val header = layoutInflater.inflate(R.layout.item_list_header, list, false) as TextView
            header.text = region.label
            list.addView(header)
            sessions.forEach { session ->
                val row = layoutInflater.inflate(R.layout.item_list_row, list, false)
                row.findViewById<TextView>(R.id.rowTitle).text =
                    getString(R.string.library_row_title, session.name, session.durationMinutes)
                row.findViewById<TextView>(R.id.rowSubtitle).text = session.oneLineWhy
                row.setOnClickListener { confirm(session) }
                list.addView(row)
            }
        }
    }

    private fun confirm(session: LibrarySession) {
        if (!settings.isFtpSet) {
            askForFtpFirst()
            return
        }
        val ftp = settings.ftpWatts
        val built = session.build(ftp, settings.recoverySetting)
        val message = buildString {
            append(session.oneLineWhy)
            append("\n\n")
            append(getString(R.string.library_confirm_ftp, ftp))
            append("\n")
            append(targetSummary(built.blocks))
            append("\n")
            append(getString(R.string.library_confirm_minutes, session.durationMinutes))
            if (built.blocks.any { it is com.mywatt.app.engine.Recovery }) {
                append("\n")
                append(getString(R.string.library_confirm_recovery, settings.recoverySetting.label))
            }
        }
        val density = resources.displayMetrics.density
        val profile = SessionProfileView(this).apply {
            setSession(built)
            val side = (24 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (72 * density).toInt(),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(session.name)
            .setMessage(message)
            .setView(profile)
            .setNegativeButton(R.string.ftp_dialog_cancel, null)
            .setNeutralButton(R.string.library_change_ftp) { _, _ ->
                showSettingsDialog(this, settings) {
                    renderFtp()
                    confirm(session)
                }
            }
            .setPositiveButton(R.string.session_start) { _, _ -> start(session) }
            .show()
    }

    /** One line on what the rider will be asked for at this FTP. */
    private fun targetSummary(blocks: List<com.mywatt.app.engine.Block>): String {
        blocks.filterIsInstance<Ramp>().firstOrNull()?.let {
            return getString(R.string.library_confirm_ramp, it.startWatts, it.stepWatts)
        }
        val reps = blocks.filterIsInstance<Rep>()
        if (reps.any { it.kind == RepKind.SELF_PACED }) return getString(R.string.library_confirm_self_paced)
        val low = reps.minOf { it.targetWatts }
        val high = reps.maxOf { it.targetWatts }
        return if (low == high) {
            getString(R.string.library_confirm_target, low)
        } else {
            getString(R.string.library_confirm_targets, low, high)
        }
    }

    private fun start(session: LibrarySession) {
        // Read again: the rider may have changed FTP or the recovery setting just now.
        ActiveSession.start(session.build(settings.ftpWatts, settings.recoverySetting))
        startActivity(Intent(this, SessionActivity::class.java))
        finish()
    }
}
