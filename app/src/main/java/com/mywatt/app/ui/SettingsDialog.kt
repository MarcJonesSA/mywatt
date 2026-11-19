package com.mywatt.app.ui

import android.app.Activity
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.mywatt.app.R
import com.mywatt.app.data.RiderSettings
import com.mywatt.app.engine.RecoverySetting
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
    val recoveryGroup = view.findViewById<RadioGroup>(R.id.recoverySettingGroup)
    RecoverySetting.entries.forEach { setting ->
        recoveryGroup.addView(
            MaterialRadioButton(activity).apply {
                id = View.generateViewId()
                tag = setting
                text = activity.getString(
                    R.string.recovery_setting_option,
                    setting.label,
                    (setting.releaseFraction * 100).roundToInt(),
                    setting.minRecoverySeconds,
                )
                isChecked = setting == settings.recoverySetting
            }
        )
    }

    fun complain(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    MaterialAlertDialogBuilder(activity)
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
            recoveryGroup.findViewById<View>(recoveryGroup.checkedRadioButtonId)
                ?.let { settings.recoverySetting = it.tag as RecoverySetting }
            Ride.current?.smoother?.windowSeconds = smoothing
            onSaved()
        }
        .show()
}
