package com.mywatt.app.session

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mywatt.app.R
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.databinding.ActivitySessionBinding
import com.mywatt.app.engine.Band
import com.mywatt.app.engine.Recovery
import com.mywatt.app.engine.ScreenMode
import com.mywatt.app.engine.RecoveryPhase
import com.mywatt.app.engine.SessionController
import com.mywatt.app.engine.SessionResult
import com.mywatt.app.engine.SessionState
import com.mywatt.app.ui.ResultsTable
import com.mywatt.app.ui.padForSystemBars
import com.mywatt.app.ui.shareLatestLogs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The training-session screen: big numbers, no dial. It displays the [SessionController]
 * held by [ActiveSession]; the ride screen underneath keeps the Bluetooth link and feeds
 * the controller from its 1 Hz loop. When the session ends the live page gives way to a
 * results table, a verdict line and the usual share button.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var controller: SessionController

    /** Dial sessions lead with the rev counter, NumberFirst ones with the big watts number. */
    private val dialMode: Boolean get() = controller.session.screenMode == ScreenMode.Dial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = ActiveSession.controller.value
        if (active == null) {
            finish()
            return
        }
        controller = active
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (dialMode) {
            binding.revCounter.setFtp(controller.session.ftpWatts ?: RiderSettings(this).ftpWatts)
            binding.revCounter.visibility = View.VISIBLE
            binding.wattsValue.visibility = View.GONE
            binding.wattsUnit.visibility = View.GONE
        }

        binding.pauseButton.setOnClickListener { controller.togglePause() }
        binding.endButton.setOnClickListener { controller.end() }
        binding.shareResultsButton.setOnClickListener { shareLatestLogs(this) }
        binding.doneButton.setOnClickListener { leave() }

        // Back during a session pauses it rather than abandoning it; End is the way out.
        onBackPressedDispatcher.addCallback(this) {
            if (controller.ended) {
                leave()
            } else {
                controller.setPaused(true)
                Toast.makeText(this@SessionActivity, R.string.session_back_paused, Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(controller.state, controller.paused, ActiveSession.live) { state, paused, live ->
                    Triple(state, paused, live)
                }.collect { (state, paused, live) -> renderLive(state, paused, live) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.result.collect { result -> if (result != null) renderResults(result) }
            }
        }
    }

    private fun leave() {
        ActiveSession.clear()
        finish()
    }

    // ------------------------------------------------------------- live page

    private fun renderLive(state: SessionState, paused: Boolean, live: LiveReadout?) {
        val block = state.block
        val inRep = state.targetWatts != null
        val inRecovery = block is Recovery

        binding.blockTitle.text = block?.label ?: getString(R.string.session_complete)
        binding.repLine.text = when {
            state.rampStep != null && state.targetWatts != null ->
                getString(R.string.session_ramp_step, state.rampStep, state.targetWatts)
            state.repNumber > 0 && state.setNumber > 0 ->
                getString(
                    R.string.session_set_rep_of,
                    state.setNumber, state.session.setCount, state.repNumber, state.session.repCount,
                )
            state.repNumber > 0 && state.session.repCount > 0 ->
                getString(R.string.session_rep_of, state.repNumber, state.session.repCount)
            else -> state.session.name
        }
        binding.statusLine.text = when {
            paused -> getString(R.string.session_paused)
            live == null -> getString(R.string.session_waiting)
            else -> state.statusLine
        }

        binding.wattsValue.text = live?.smoothedWatts?.toString() ?: getString(R.string.no_reading)
        if (dialMode) binding.revCounter.setPower(live?.smoothedWatts)
        binding.targetValue.text = when {
            state.targetWatts != null -> getString(R.string.session_target, state.targetWatts)
            state.easyTargetWatts != null -> getString(R.string.session_easy_target, state.easyTargetWatts)
            else -> getString(R.string.session_easy)
        }
        val cadenceTarget = state.cadenceTarget
        if (cadenceTarget != null) {
            binding.cadenceLine.text = getString(
                R.string.session_cadence,
                live?.cadenceRpm?.toString() ?: getString(R.string.no_reading),
                cadenceTarget,
            )
            binding.cadenceLine.visibility = View.VISIBLE
        } else {
            binding.cadenceLine.visibility = View.GONE
        }
        binding.targetValue.setTextColor(
            ContextCompat.getColor(this, if (inRep) R.color.text_primary else R.color.text_secondary)
        )

        val background = ContextCompat.getColor(this, R.color.background)
        val band = state.band?.takeIf { live?.smoothedWatts != null }
        // Portrait tints the whole page; landscape only the watts column.
        (binding.bandPanel ?: binding.livePanel).setBackgroundColor(
            if (band != null) ColorUtils.blendARGB(background, bandColor(band), BAND_TINT) else background
        )
        binding.gearNag.visibility = if (state.gearNag) View.VISIBLE else View.INVISIBLE

        val hr = live?.hrBpm
        binding.hrValue.text = hr?.let { getString(R.string.hr_value, it) } ?: getString(R.string.hr_unknown)
        binding.hrValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (hr != null && live?.hrContactLost == false) R.color.text_primary else R.color.text_secondary,
            )
        )
        val gear = live?.gear
        binding.gearValue.text = gear?.let { getString(R.string.gear_value, it.gear) } ?: getString(R.string.gear_unknown)
        binding.gearValue.setTextColor(
            ContextCompat.getColor(this, if (gear?.confirmed == true) R.color.text_primary else R.color.text_secondary)
        )

        if (inRecovery) {
            val hrText = hr?.toString() ?: getString(R.string.no_reading)
            val elapsed = clock(state.secondsInBlock)
            binding.recoveryLine.text = when {
                state.gateHr != null -> getString(R.string.session_recover, hrText, state.gateHr.toString(), elapsed)
                state.recoveryPhase == RecoveryPhase.BUILDING && hr != null ->
                    getString(R.string.session_recover, hrText, getString(R.string.session_building), elapsed)
                else -> getString(R.string.session_recover_no_gate, hrText, elapsed)
            }
            binding.recoveryLine.visibility = View.VISIBLE
            val drops = state.reps.lastOrNull()?.minuteDrops.orEmpty()
            binding.minuteDrops.text = drops.joinToString(" | ") { formatDrop(it) }
            binding.minuteDrops.visibility = if (drops.isEmpty()) View.INVISIBLE else View.VISIBLE
        } else {
            binding.recoveryLine.visibility = View.INVISIBLE
            binding.minuteDrops.visibility = View.INVISIBLE
        }

        if (inRecovery) {
            binding.timeLabel.setText(R.string.session_time_elapsed)
            binding.timeValue.text = clock(state.secondsInBlock)
        } else {
            binding.timeLabel.setText(R.string.session_time_left)
            binding.timeValue.text = state.secondsLeft?.let { clock(it) } ?: getString(R.string.no_reading)
        }

        binding.pauseButton.setText(if (paused) R.string.action_resume else R.string.action_pause)
    }

    // ---------------------------------------------------------- results page

    private fun renderResults(result: SessionResult) {
        binding.livePanel.visibility = View.GONE
        binding.resultsPanel.visibility = View.VISIBLE
        binding.resultsTitle.setText(if (result.completed) R.string.session_complete else R.string.session_ended)
        binding.verdictText.text = result.verdict + System.lineSeparator() +
            getString(R.string.results_recovery_setting, result.recoverySetting.label)
        result.ftpEstimate?.let { offerFtp(it) }

        ResultsTable.render(binding.resultsTable, result.reps)
    }

    /** Asks once whether to save an FTP test's estimate as the rider's FTP. */
    private fun offerFtp(estimate: Int) {
        if (ActiveSession.ftpOfferAnswered) return
        if (estimate !in RiderSettings.MIN_FTP..RiderSettings.MAX_FTP) return
        val settings = RiderSettings(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ftp_offer_title, estimate))
            .setMessage(getString(R.string.ftp_offer_message, estimate, settings.ftpWatts))
            .setNegativeButton(getString(R.string.ftp_offer_keep, settings.ftpWatts)) { _, _ ->
                ActiveSession.ftpOfferAnswered = true
            }
            .setPositiveButton(R.string.ftp_offer_save) { _, _ ->
                ActiveSession.ftpOfferAnswered = true
                settings.ftpWatts = estimate
                Toast.makeText(this, getString(R.string.ftp_saved, estimate), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // --------------------------------------------------------------- helpers

    @ColorInt
    private fun bandColor(band: Band): Int = ResultsTable.bandColor(this, band)

    /** A drop of 15 bpm reads "-15"; a rise over the minute reads "+4". */
    private fun formatDrop(drop: Int): String = if (drop >= 0) "-$drop" else "+${-drop}"

    private fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.UK, "%d:%02d", s / 60, s % 60)
    }

    companion object {
        /** How strongly the band colour shows through the background during a rep. */
        private const val BAND_TINT = 0.28f
    }
}
