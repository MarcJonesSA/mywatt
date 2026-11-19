package com.mywatt.app.profile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.mywatt.app.R
import com.mywatt.app.data.EntryProfile
import com.mywatt.app.data.RiderProfile
import com.mywatt.app.data.TimeOff
import com.mywatt.app.data.WeeklyHours
import com.mywatt.app.databinding.ActivityOnboardingBinding
import com.mywatt.app.ui.padForSystemBars

/**
 * The first-launch questions, two screens. Q1: riding regularly now? Yes asks hours per
 * week, No asks how long since. Q2: structured intervals before? Each answer advances on
 * tap; the last stores the answers and the [EntryProfile] they give. Skip leaves it unset
 * (it can be set later in the profile) and does not ask again.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var profile: RiderProfile

    private var ridingNow: Boolean? = null
    private var hours: WeeklyHours? = null
    private var timeOff: TimeOff? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        profile = RiderProfile(this)
        binding.skipButton.setOnClickListener {
            profile.entryQuestionsDone = true
            finish()
        }
        savedInstanceState?.let { state ->
            ridingNow = if (state.containsKey(STATE_RIDING)) state.getBoolean(STATE_RIDING) else null
            hours = state.getString(STATE_HOURS)?.let { WeeklyHours.valueOf(it) }
            timeOff = state.getString(STATE_TIME_OFF)?.let { TimeOff.valueOf(it) }
        }
        if (hours != null || timeOff != null) showQuestionTwo() else showQuestionOne()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        ridingNow?.let { outState.putBoolean(STATE_RIDING, it) }
        hours?.let { outState.putString(STATE_HOURS, it.name) }
        timeOff?.let { outState.putString(STATE_TIME_OFF, it.name) }
    }

    private fun showQuestionOne() {
        binding.stepLabel.text = getString(R.string.onboarding_step, 1, 2)
        binding.questionText.setText(R.string.onboarding_q1)
        answers(binding.answers, listOf(getString(R.string.onboarding_yes), getString(R.string.onboarding_no))) { i ->
            ridingNow = i == 0
            showFollowUp()
        }
        binding.followUpText.visibility = View.GONE
        binding.followUpAnswers.removeAllViews()
        ridingNow?.let { showFollowUp() }
    }

    private fun showFollowUp() {
        val yes = ridingNow == true
        binding.followUpText.setText(if (yes) R.string.onboarding_q1_hours else R.string.onboarding_q1_since)
        binding.followUpText.visibility = View.VISIBLE
        val labels = if (yes) WeeklyHours.entries.map { it.label } else TimeOff.entries.map { it.label }
        answers(binding.followUpAnswers, labels) { i ->
            if (yes) hours = WeeklyHours.entries[i] else timeOff = TimeOff.entries[i]
            showQuestionTwo()
        }
        highlight(binding.answers, if (yes) 0 else 1)
    }

    private fun showQuestionTwo() {
        binding.stepLabel.text = getString(R.string.onboarding_step, 2, 2)
        binding.questionText.setText(R.string.onboarding_q2)
        binding.followUpText.visibility = View.GONE
        binding.followUpAnswers.removeAllViews()
        answers(binding.answers, listOf(getString(R.string.onboarding_yes), getString(R.string.onboarding_no))) { i ->
            finishWith(intervalsBefore = i == 0)
        }
    }

    private fun finishWith(intervalsBefore: Boolean) {
        val riding = ridingNow ?: return
        profile.ridingNow = riding
        profile.weeklyHours = hours
        profile.timeOff = timeOff
        profile.intervalsBefore = intervalsBefore
        profile.entryProfile = EntryProfile.classify(riding, timeOff, intervalsBefore)
        profile.entryQuestionsDone = true
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back steps back through the questions, then leaves without answering.
        when {
            hours != null || timeOff != null -> {
                hours = null
                timeOff = null
                showQuestionOne()
            }
            else -> super.onBackPressed()
        }
    }

    private fun answers(container: LinearLayout, labels: List<String>, onTap: (Int) -> Unit) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        labels.forEachIndexed { i, label ->
            container.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    textSize = 18f
                    isAllCaps = false
                    cornerRadius = (16 * density).toInt()
                    setOnClickListener { onTap(i) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (60 * density).toInt()).apply {
                    topMargin = (8 * density).toInt()
                },
            )
        }
    }

    private fun highlight(container: LinearLayout, chosen: Int) {
        for (i in 0 until container.childCount) {
            container.getChildAt(i).alpha = if (i == chosen) 1f else 0.45f
        }
    }

    companion object {
        private const val STATE_RIDING = "riding"
        private const val STATE_HOURS = "hours"
        private const val STATE_TIME_OFF = "time_off"
    }
}
