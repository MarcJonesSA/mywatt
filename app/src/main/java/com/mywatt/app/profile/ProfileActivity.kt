package com.mywatt.app.profile

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.mywatt.app.R
import com.mywatt.app.data.EntryProfile
import com.mywatt.app.data.RiderProfile
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.data.Sex
import com.mywatt.app.data.history.HistoryDatabase
import com.mywatt.app.databinding.ActivityProfileBinding
import com.mywatt.app.ui.padForSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rider profile: name, sex, date of birth, weight, height, FTP, max and resting HR, and
 * the entry profile from the first-launch questions. Max HR stays blank until the rider
 * sets it; the screen offers the highest HR seen in any saved session, never 220 - age.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var profile: RiderProfile
    private lateinit var settings: RiderSettings
    private var dateOfBirth: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        profile = RiderProfile(this)
        settings = RiderSettings(this)

        binding.sexInput.setSimpleItems(Sex.entries.map { it.label }.toTypedArray())
        binding.entryInput.setSimpleItems(EntryProfile.entries.map { it.label }.toTypedArray())
        if (savedInstanceState == null) fill()
        dateOfBirth = savedInstanceState?.getString(STATE_DOB) ?: profile.dateOfBirth

        binding.dobInput.setOnClickListener { pickDate() }
        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { if (save()) finish() }
        offerHighestHr()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DOB, dateOfBirth)
    }

    private fun fill() {
        binding.nameInput.setText(profile.name.orEmpty())
        binding.sexInput.setText(profile.sex?.label.orEmpty(), false)
        binding.dobInput.setText(profile.dateOfBirth.orEmpty())
        binding.weightInput.setText(profile.weightKg?.let { formatWeight(it) }.orEmpty())
        binding.heightInput.setText(profile.heightCm?.toString().orEmpty())
        binding.ftpInput.setText(if (settings.isFtpSet) settings.ftpWatts.toString() else "")
        binding.maxHrInput.setText(profile.maxHr?.toString().orEmpty())
        binding.restingHrInput.setText(profile.restingHr?.toString().orEmpty())
        binding.entryInput.setText(profile.entryProfile?.label.orEmpty(), false)
    }

    private fun formatWeight(kg: Double): String =
        if (kg % 1.0 == 0.0) kg.toInt().toString() else "%.1f".format(java.util.Locale.UK, kg)

    private fun pickDate() {
        // yyyy-MM-dd; java.time needs API 26 and this app runs from 24.
        val parts = dateOfBirth?.split('-')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: listOf(1980, 1, 1)
        DatePickerDialog(this, { _, year, month, day ->
            dateOfBirth = "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, month + 1, day)
            binding.dobInput.setText(dateOfBirth)
        }, parts[0], parts[1] - 1, parts[2]).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /** "Highest seen: 172 — use this?" under a blank max HR, from the saved sessions. */
    private fun offerHighestHr() {
        lifecycleScope.launch {
            val highest = withContext(Dispatchers.IO) {
                runCatching { HistoryDatabase.get(this@ProfileActivity).dao().highestHr() }.getOrNull()
            } ?: return@launch
            if (binding.maxHrInput.text.isNullOrBlank()) {
                binding.maxHrSuggestion.text = getString(R.string.profile_max_hr_seen, highest)
                binding.maxHrSuggestion.visibility = View.VISIBLE
                binding.maxHrSuggestion.setOnClickListener {
                    binding.maxHrInput.setText(highest.toString())
                    binding.maxHrSuggestion.visibility = View.GONE
                }
            }
        }
    }

    /** Checks and stores the form; false (with a message) if a number is out of range. */
    private fun save(): Boolean {
        fun number(text: CharSequence?): String? = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val weight = number(binding.weightInput.text)?.replace(',', '.')?.toDoubleOrNull()
        val height = number(binding.heightInput.text)?.toIntOrNull()
        val ftp = number(binding.ftpInput.text)?.toIntOrNull()
        val maxHr = number(binding.maxHrInput.text)?.toIntOrNull()
        val restingHr = number(binding.restingHrInput.text)?.toIntOrNull()
        val problem = when {
            number(binding.weightInput.text) != null && (weight == null || weight !in 25.0..250.0) -> R.string.profile_bad_weight
            number(binding.heightInput.text) != null && (height == null || height !in 100..230) -> R.string.profile_bad_height
            number(binding.ftpInput.text) != null && (ftp == null || ftp !in RiderSettings.MIN_FTP..RiderSettings.MAX_FTP) ->
                R.string.profile_bad_ftp
            number(binding.maxHrInput.text) != null && (maxHr == null || maxHr !in 100..230) -> R.string.profile_bad_max_hr
            number(binding.restingHrInput.text) != null && (restingHr == null || restingHr !in 25..120) ->
                R.string.profile_bad_resting_hr
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return false
        }
        profile.name = binding.nameInput.text?.toString()?.trim()
        profile.sex = Sex.entries.firstOrNull { it.label == binding.sexInput.text.toString() }
        profile.dateOfBirth = dateOfBirth
        profile.weightKg = weight
        profile.heightCm = height
        profile.maxHr = maxHr
        profile.restingHr = restingHr
        profile.entryProfile = EntryProfile.entries.firstOrNull { it.label == binding.entryInput.text.toString() }
        if (ftp != null) settings.ftpWatts = ftp
        return true
    }

    companion object {
        private const val STATE_DOB = "dob"
    }
}
