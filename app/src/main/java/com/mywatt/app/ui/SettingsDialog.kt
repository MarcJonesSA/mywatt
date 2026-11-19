package com.mywatt.app.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.mywatt.app.R
import com.mywatt.app.data.RiderSettings
import com.google.android.material.materialswitch.MaterialSwitch
import com.mywatt.app.engine.CueSettings
import com.mywatt.app.engine.RecoverySetting
import com.mywatt.app.profile.ProfileActivity
import com.mywatt.app.ride.Ride
import kotlin.math.roundToInt

/**
 * The settings dialog: FTP, power smoothing and the recovery setting. Shared by the ride
 * screen and the session library (which opens it first if FTP has never been set).
 * [onSaved] runs after valid values are stored; [onCancelled] if the rider backs out.
 */
fun showSettingsDialog(
    activity: Activity,
    settings: RiderSettings,
    title: String = activity.getString(R.string.ftp_dialog_title),
    message: String = activity.getString(R.string.ftp_dialog_message, RiderSettings.MIN_FTP, RiderSettings.MAX_FTP),
    onCancelled: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val view = activity.layoutInflater.inflate(R.layout.dialog_ftp, null)
    val input = view.findViewById<TextInputEditText>(R.id.ftpInput)
    input.setText(settings.ftpWatts.toString())
    input.setSelection(input.text?.length ?: 0)
    val smoothingInput = view.findViewById<TextInputEditText>(R.id.smoothingInput)
    smoothingInput.setText(settings.smoothingSeconds.toString())
    // One row per setting in rider language, with the numbers behind an (i). The rows hold
    // more than a radio button, so the one-of-four choice is kept by hand, not by a RadioGroup.
    val recoveryGroup = view.findViewById<LinearLayout>(R.id.recoverySettingGroup)
    val density = activity.resources.displayMetrics.density
    val radios = RecoverySetting.entries.map { setting ->
        val radio = MaterialRadioButton(activity).apply {
            tag = setting
            text = activity.getString(R.string.recovery_setting_row, setting.label, setting.blurb)
            isChecked = setting == settings.recoverySetting
        }
        val info = TextView(activity).apply {
            text = "ⓘ"
            textSize = 20f
            setTextColor(ContextCompat.getColor(activity, R.color.accent))
            val pad = (12 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            contentDescription = activity.getString(R.string.recovery_setting_info_more, setting.label)
            setOnClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(setting.label)
                    .setMessage(
                        activity.getString(
                            R.string.recovery_setting_info,
                            (setting.releaseFraction * 100).roundToInt(),
                            setting.minRecoverySeconds,
                            setting.timeoutSeconds,
                        )
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        recoveryGroup.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(radio, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(info)
            }
        )
        radio
    }
    radios.forEach { radio ->
        radio.setOnCheckedChangeListener { button, checked ->
            if (checked) radios.filter { it !== button }.forEach { it.isChecked = false }
        }
    }

    val cues = settings.cueSettings
    val cuesSwitch = view.findViewById<MaterialSwitch>(R.id.cuesSwitch)
    val tierSwitches = listOf(R.id.cuesShortSwitch, R.id.cuesMidSwitch, R.id.cuesLongSwitch, R.id.cuesBeepSwitch)
        .map { view.findViewById<MaterialSwitch>(it) }
    val (shortSwitch, midSwitch, longSwitch, beepSwitch) = tierSwitches
    cuesSwitch.isChecked = cues.enabled
    shortSwitch.isChecked = cues.shortTier
    midSwitch.isChecked = cues.midTier
    longSwitch.isChecked = cues.longTier
    beepSwitch.isChecked = cues.beepOnly
    fun syncTiers() = tierSwitches.forEach { it.isEnabled = cuesSwitch.isChecked }
    syncTiers()
    cuesSwitch.setOnCheckedChangeListener { _, _ -> syncTiers() }

    fun complain(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setMessage(message)
        .setView(view)
        .setOnCancelListener { onCancelled() }
        .setNegativeButton(R.string.ftp_dialog_cancel) { _, _ -> onCancelled() }
        .setPositiveButton(R.string.ftp_dialog_save) { _, _ ->
            val entered = input.text?.toString()?.trim()?.toIntOrNull()
            if (entered == null || entered !in RiderSettings.MIN_FTP..RiderSettings.MAX_FTP) {
                complain(activity.getString(R.string.ftp_invalid, RiderSettings.MIN_FTP, RiderSettings.MAX_FTP))
                onCancelled()
                return@setPositiveButton
            }
            val smoothing = smoothingInput.text?.toString()?.trim()?.toIntOrNull()
            if (smoothing == null ||
                smoothing !in RiderSettings.MIN_SMOOTHING_SECONDS..RiderSettings.MAX_SMOOTHING_SECONDS
            ) {
                complain(
                    activity.getString(
                        R.string.smoothing_invalid,
                        RiderSettings.MIN_SMOOTHING_SECONDS,
                        RiderSettings.MAX_SMOOTHING_SECONDS,
                    )
                )
                onCancelled()
                return@setPositiveButton
            }
            settings.ftpWatts = entered
            settings.smoothingSeconds = smoothing
            radios.firstOrNull { it.isChecked }?.let { settings.recoverySetting = it.tag as RecoverySetting }
            settings.cueSettings = CueSettings(
                enabled = cuesSwitch.isChecked,
                shortTier = shortSwitch.isChecked,
                midTier = midSwitch.isChecked,
                longTier = longSwitch.isChecked,
                beepOnly = beepSwitch.isChecked,
            )
            Ride.current?.smoother?.windowSeconds = smoothing
            onSaved()
        }
        .show()
    view.findViewById<View>(R.id.profileButton).setOnClickListener {
        // The profile edits FTP too, so the dialog closes rather than show a stale value.
        dialog.dismiss()
        onCancelled()
        activity.startActivity(Intent(activity, ProfileActivity::class.java))
    }
}
