package org.koitharu.kotatsu.main.ui.protect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.DefaultTextWatcher
import org.koitharu.kotatsu.core.ui.util.IosUiHelper.pulsePinDot
import org.koitharu.kotatsu.core.ui.util.IosUiHelper.setupPressAnimation
import org.koitharu.kotatsu.core.ui.util.IosUiHelper.shakeIos
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityProtectBinding
import com.google.android.material.R as materialR

@AndroidEntryPoint
class ProtectActivity :
	BaseActivity<ActivityProtectBinding>(),
	TextView.OnEditorActionListener,
	DefaultTextWatcher,
	View.OnClickListener,
	AuthenticationResultCallback {

	private val viewModel by viewModels<ProtectViewModel>()
	private var canUseBiometric = false

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)

	private lateinit var pinDots: List<ImageView>

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivityProtectBinding.inflate(layoutInflater))

		pinDots = listOf(
			viewBinding.pinDot1,
			viewBinding.pinDot2,
			viewBinding.pinDot3,
			viewBinding.pinDot4,
			viewBinding.pinDot5,
			viewBinding.pinDot6,
		)

		viewBinding.editPassword.setOnEditorActionListener(this)
		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.buttonNext.setOnClickListener(this)
		viewBinding.buttonCancel.setOnClickListener(this)

		val isNumeric = viewModel.isNumericPassword
		viewBinding.layoutPinDots.isVisible = isNumeric
		viewBinding.layoutKeypad.isVisible = isNumeric
		viewBinding.layoutPassword.isGone = isNumeric
		viewBinding.buttonNext.isGone = isNumeric

		val targetLength = viewModel.passwordLength.coerceAtLeast(4)
		viewBinding.pinDot5.isVisible = isNumeric && targetLength >= 5
		viewBinding.pinDot6.isVisible = isNumeric && targetLength >= 6

		viewBinding.editPassword.inputType = if (isNumeric) {
			EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
		} else {
			EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
		}

		setupKeypad()

		viewModel.onError.observeEvent(this, this::onError)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.onUnlockSuccess.observeEvent(this) {
			val intent = intent.getParcelableExtraCompat<Intent>(EXTRA_INTENT)
			startActivity(intent)
			finishAfterTransition()
		}
		lifecycleScope.launch {
			withResumed {
				canUseBiometric = useFingerprint()
				updateEndIcon()
				updateKeypadActionButton(viewBinding.editPassword.text?.length ?: 0)
				if (!canUseBiometric && !isNumeric) {
					viewBinding.editPassword.requestFocus()
				}
			}
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
			R.id.button_next -> viewModel.tryUnlock(viewBinding.editPassword.text?.toString().orEmpty())
			R.id.button_cancel -> finish()
			materialR.id.text_input_end_icon -> useFingerprint()
		}
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
		viewBinding.layoutPassword.error = null
		viewBinding.buttonNext.isEnabled = !s.isNullOrEmpty()
		updateEndIcon()

		viewBinding.textViewSubtitle.setText(R.string.enter_password)
		viewBinding.textViewSubtitle.setTextColor(getColor(R.color.ios_label_secondary))

		val length = s?.length ?: 0
		val targetLength = if (viewModel.isNumericPassword) viewModel.passwordLength.coerceAtLeast(4) else 4
		val visibleDotsCount = if (targetLength >= 6) 6 else if (targetLength == 5) 5 else 4

		for (i in 0 until visibleDotsCount) {
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

		updateKeypadActionButton(length)

		if (viewModel.isNumericPassword && length == targetLength) {
			viewModel.tryUnlock(s?.toString().orEmpty())
		}
	}

	override fun onAuthResult(result: AuthenticationResult) {
		if (result.isSuccess()) {
			viewModel.unlock()
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
			onKeypadActionClick()
		}
		viewBinding.buttonKeypadAction.setOnLongClickListener {
			onKeypadActionLongClick()
		}
		viewBinding.buttonKeypadAction.setupPressAnimation()
	}

	private fun onKeypadDigit(digit: String) {
		val current = viewBinding.editPassword.text ?: return
		val targetLength = if (viewModel.isNumericPassword) viewModel.passwordLength.coerceAtLeast(4) else 24
		if (current.length < targetLength) {
			viewBinding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
			current.append(digit)
		}
	}

	private fun onKeypadActionClick() {
		val current = viewBinding.editPassword.text
		if (current.isNullOrEmpty()) {
			if (canUseBiometric) {
				useFingerprint()
			}
		} else {
			viewBinding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
			current.delete(current.length - 1, current.length)
		}
	}

	private fun onKeypadActionLongClick(): Boolean {
		val current = viewBinding.editPassword.text
		return if (!current.isNullOrEmpty()) {
			viewBinding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
			current.clear()
			true
		} else {
			false
		}
	}

	private fun updateKeypadActionButton(length: Int) {
		if (length == 0) {
			if (canUseBiometric) {
				viewBinding.imageKeypadAction.setImageResource(R.drawable.ic_ios_face_id)
				viewBinding.imageKeypadAction.contentDescription = getString(androidx.biometric.R.string.use_biometric_label)
				viewBinding.buttonKeypadAction.isVisible = true
			} else {
				viewBinding.buttonKeypadAction.isInvisible = true
			}
		} else {
			viewBinding.imageKeypadAction.setImageResource(R.drawable.ic_ios_delete)
			viewBinding.imageKeypadAction.contentDescription = getString(R.string.ios_delete)
			viewBinding.buttonKeypadAction.isVisible = true
		}
	}

	private fun onError(e: Throwable) {
		viewBinding.layoutPassword.error = e.getDisplayMessage(resources)
		viewBinding.textViewSubtitle.setText(R.string.ios_incorrect_passcode)
		viewBinding.textViewSubtitle.setTextColor(getColor(R.color.ios_accent_red))

		if (viewModel.isNumericPassword) {
			viewBinding.layoutPinDots.shakeIos {
				viewBinding.editPassword.text?.clear()
			}
		}
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.layoutPassword.isEnabled = !isLoading
		viewBinding.layoutKeypad.isEnabled = !isLoading
		viewBinding.buttonKeypadAction.isEnabled = !isLoading
	}

	private fun useFingerprint(): Boolean {
		if (!viewModel.isBiometricEnabled) {
			return false
		}
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) != BIOMETRIC_SUCCESS) {
			return false
		}
		val request = AuthenticationRequest.biometricRequest(
			title = getString(R.string.app_name),
			authFallback = Biometric.Fallback.NegativeButton(getString(android.R.string.cancel)),
			init = {
				setMinStrength(Biometric.Strength.Class2)
				setIsConfirmationRequired(false)
			},
		)
		biometricPrompt.launch(request)
		return true
	}

	private fun updateEndIcon() = with(viewBinding.layoutPassword) {
		val isFingerprintIcon = canUseBiometric && viewBinding.editPassword.text.isNullOrEmpty()
		if (isFingerprintIcon == (endIconMode == TextInputLayout.END_ICON_CUSTOM)) {
			return@with
		}
		if (isFingerprintIcon) {
			endIconMode = TextInputLayout.END_ICON_CUSTOM
			setEndIconDrawable(androidx.biometric.R.drawable.fingerprint_dialog_fp_icon)
			endIconContentDescription = getString(androidx.biometric.R.string.use_biometric_label)
			setEndIconOnClickListener(this@ProtectActivity)
		} else {
			setEndIconOnClickListener(null)
			setEndIconDrawable(0)
			endIconContentDescription = null
			endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
		}
	}

	companion object {

		private const val EXTRA_INTENT = "src_intent"

		fun newIntent(context: Context, sourceIntent: Intent): Intent {
			return Intent(context, ProtectActivity::class.java)
				.putExtra(EXTRA_INTENT, sourceIntent)
		}
	}
}
