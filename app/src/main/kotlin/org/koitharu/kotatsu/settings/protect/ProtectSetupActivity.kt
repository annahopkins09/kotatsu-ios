package org.koitharu.kotatsu.settings.protect

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.DefaultTextWatcher
import org.koitharu.kotatsu.core.ui.util.IosUiHelper.pulsePinDot
import org.koitharu.kotatsu.core.ui.util.IosUiHelper.setupPressAnimation
import org.koitharu.kotatsu.core.ui.util.IosUiHelper.shakeIos
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivitySetupProtectBinding

private const val MIN_PASSWORD_LENGTH = 4

@AndroidEntryPoint
class ProtectSetupActivity :
	BaseActivity<ActivitySetupProtectBinding>(),
	DefaultTextWatcher,
	View.OnClickListener,
	TextView.OnEditorActionListener,
	CompoundButton.OnCheckedChangeListener {

	private val viewModel by viewModels<ProtectSetupViewModel>()
	private var isKeypadMode = true
	private lateinit var pinDots: List<ImageView>

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivitySetupProtectBinding.inflate(layoutInflater))

		pinDots = listOf(
			viewBinding.pinDot1,
			viewBinding.pinDot2,
			viewBinding.pinDot3,
			viewBinding.pinDot4,
		)

		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.editPassword.setOnEditorActionListener(this)
		viewBinding.buttonNext.setOnClickListener(this)
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonPasscodeOptions.setOnClickListener(this)

		viewBinding.switchBiometric.isChecked = viewModel.isBiometricEnabled
		viewBinding.switchBiometric.setOnCheckedChangeListener(this)

		setupKeypad()
		updateModeUI()

		viewModel.isSecondStep.observe(this, this::onStepChanged)
		viewModel.onPasswordSet.observeEvent(this) {
			finishAfterTransition()
		}
		viewModel.onPasswordMismatch.observeEvent(this) {
			viewBinding.editPassword.error = getString(R.string.passwords_mismatch)
			viewBinding.textViewSubtitle.setText(R.string.passwords_mismatch)
			viewBinding.textViewSubtitle.setTextColor(getColor(R.color.ios_accent_red))
			if (isKeypadMode) {
				viewBinding.layoutPinDots.shakeIos {
					viewBinding.editPassword.text?.clear()
				}
			}
		}
		viewModel.onClearText.observeEvent(this) {
			viewBinding.editPassword.text?.clear()
			clearDots()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val basePadding = resources.getDimensionPixelOffset(R.dimen.screen_padding)
		viewBinding.root.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> finish()
			R.id.button_next -> viewModel.onNextClick(
				password = viewBinding.editPassword.text?.toString() ?: return,
			)
			R.id.button_passcode_options -> {
				isKeypadMode = !isKeypadMode
				viewBinding.editPassword.text?.clear()
				clearDots()
				updateModeUI()
			}
		}
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		viewModel.setBiometricEnabled(isChecked)
	}

	override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
		return if (actionId == EditorInfo.IME_ACTION_DONE && viewBinding.buttonNext.isEnabled) {
			viewBinding.buttonNext.performClick()
			true
		} else {
			false
		}
	}

	override fun afterTextChanged(s: Editable?) {
		viewBinding.editPassword.error = null
		val length = s?.length ?: 0
		val isEnoughLength = length >= MIN_PASSWORD_LENGTH
		viewBinding.buttonNext.isEnabled = isEnoughLength
		viewBinding.layoutPassword.isHelperTextEnabled =
			!isEnoughLength || viewModel.isSecondStep.value == true

		if (viewModel.isSecondStep.value == true) {
			viewBinding.textViewSubtitle.setText(R.string.ios_reenter_passcode)
		} else {
			viewBinding.textViewSubtitle.setText(R.string.protect_application_subtitle)
		}
		viewBinding.textViewSubtitle.setTextColor(getColor(R.color.ios_label_secondary))

		if (isKeypadMode) {
			for (i in 0 until 4) {
				val dot = pinDots[i]
				val isFilled = i < length
				val wasFilled = dot.tag == true
				if (isFilled != wasFilled) {
					dot.tag = isFilled
					dot.setBackgroundResource(
						if (isFilled) R.drawable.bg_ios_pin_dot_filled else R.drawable.bg_ios_pin_dot_empty,
					)
					dot.pulsePinDot(isFilled)
				}
			}
			viewBinding.buttonKeypadAction.isVisible = length > 0

			if (length == 4) {
				viewModel.onNextClick(password = s?.toString().orEmpty())
			}
		}
	}

	private fun setupKeypad() {
		val keys = listOf(
			viewBinding.key0 to "0",
			viewBinding.key1 to "1",
			viewBinding.key2 to "2",
			viewBinding.key3 to "3",
			viewBinding.key4 to "4",
			viewBinding.key5 to "5",
			viewBinding.key6 to "6",
			viewBinding.key7 to "7",
			viewBinding.key8 to "8",
			viewBinding.key9 to "9",
		)
		keys.forEach { (view, digit) ->
			view.setOnClickListener {
				onKeypadDigit(digit)
			}
			view.setupPressAnimation()
		}

		viewBinding.buttonKeypadAction.setOnClickListener {
			onKeypadDeleteClick()
		}
		viewBinding.buttonKeypadAction.setOnLongClickListener {
			onKeypadDeleteLongClick()
		}
		viewBinding.buttonKeypadAction.setupPressAnimation()
		viewBinding.buttonKeypadAction.isVisible = false
	}

	private fun onKeypadDigit(digit: String) {
		val current = viewBinding.editPassword.text ?: return
		if (current.length < 4) {
			viewBinding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
			current.append(digit)
		}
	}

	private fun onKeypadDeleteClick() {
		val current = viewBinding.editPassword.text
		if (!current.isNullOrEmpty()) {
			viewBinding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
			current.delete(current.length - 1, current.length)
		}
	}

	private fun onKeypadDeleteLongClick(): Boolean {
		val current = viewBinding.editPassword.text
		return if (!current.isNullOrEmpty()) {
			viewBinding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
			current.clear()
			true
		} else {
			false
		}
	}

	private fun clearDots() {
		for (dot in pinDots) {
			dot.tag = false
			dot.setBackgroundResource(R.drawable.bg_ios_pin_dot_empty)
		}
		viewBinding.buttonKeypadAction.isVisible = false
	}

	private fun updateModeUI() {
		viewBinding.layoutPinDots.isVisible = isKeypadMode
		viewBinding.layoutKeypad.isVisible = isKeypadMode
		viewBinding.layoutPassword.isGone = isKeypadMode
		viewBinding.buttonNext.isGone = isKeypadMode
		viewBinding.buttonPasscodeOptions.setText(
			if (isKeypadMode) R.string.ios_use_alphanumeric else R.string.ios_use_numeric,
		)
		if (!isKeypadMode) {
			viewBinding.editPassword.requestFocus()
		}
	}

	private fun onStepChanged(isSecondStep: Boolean) {
		viewBinding.buttonCancel.isGone = isSecondStep
		viewBinding.layoutBiometricCard.isVisible = isSecondStep && isBiometricAvailable()
		viewBinding.buttonPasscodeOptions.isGone = isSecondStep

		if (isSecondStep) {
			viewBinding.textViewTitle.setText(R.string.ios_verify_passcode)
			viewBinding.textViewSubtitle.setText(R.string.ios_reenter_passcode)
			viewBinding.layoutPassword.helperText = getString(R.string.repeat_password)
			viewBinding.buttonNext.setText(R.string.confirm)
		} else {
			viewBinding.textViewTitle.setText(R.string.ios_set_passcode)
			viewBinding.textViewSubtitle.setText(R.string.protect_application_subtitle)
			viewBinding.layoutPassword.helperText = getString(R.string.password_length_hint)
			viewBinding.buttonNext.setText(R.string.next)
		}
	}

	private fun isBiometricAvailable(): Boolean {
		return packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
	}
}
