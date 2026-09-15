package org.koitharu.kotatsu.core.ui.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.AnimRes
import androidx.core.view.doOnDetach
import androidx.core.view.doOnLayout
import org.koitharu.kotatsu.R

/**
 * iOS-inspired UI utilities: spring animations, blur effects, and transitions.
 */
object IosUiHelper {

	val springInterpolator: TimeInterpolator by lazy {
		PathInterpolator(0.175f, 0.885f, 0.32f, 1.275f)
	}

	val easeOutInterpolator: TimeInterpolator by lazy {
		PathInterpolator(0.4f, 0f, 0.2f, 1f)
	}

	fun View.applyBlurEffect(radius: Float = 24f) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			setRenderEffect(
				RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
			)
		}
	}

	fun View.clearBlurEffect() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			setRenderEffect(null)
		}
	}

	fun View.playIosScaleIn() {
		withAnimationCleanup {
			startAnimation(AnimationUtils.loadAnimation(context, R.anim.ios_scale_fade_in))
		}
	}

	fun View.playIosScaleOut(@AnimRes animRes: Int = R.anim.ios_scale_fade_out) {
		withAnimationCleanup {
			startAnimation(AnimationUtils.loadAnimation(context, animRes))
		}
	}

	fun View.animateSpringShow(duration: Long = 350L) {
		withAnimationCleanup {
			alpha = 0f
			scaleX = 0.92f
			scaleY = 0.92f
			visibility = View.VISIBLE
			animate()
				.alpha(1f)
				.scaleX(1f)
				.scaleY(1f)
				.setDuration(duration)
				.setInterpolator(springInterpolator)
				.setListener(null)
				.start()
		}
	}

	fun View.animateSpringHide(duration: Long = 250L, onEnd: (() -> Unit)? = null) {
		withAnimationCleanup {
			animate()
				.alpha(0f)
				.scaleX(0.95f)
				.scaleY(0.95f)
				.setDuration(duration)
				.setInterpolator(easeOutInterpolator)
				.withEndAction {
					if (!isAttachedToWindow) {
						return@withEndAction
					}
					visibility = View.GONE
					resetIosAnimationState()
					onEnd?.invoke()
				}
				.start()
		}
	}

	fun View.animatePress(scale: Float = 0.96f) {
		withAnimationCleanup {
			animate()
				.scaleX(scale)
				.scaleY(scale)
				.setDuration(100)
				.setInterpolator(easeOutInterpolator)
				.setListener(null)
				.start()
		}
	}

	fun View.animateRelease() {
		withAnimationCleanup {
			animate()
				.scaleX(1f)
				.scaleY(1f)
				.setDuration(200)
				.setInterpolator(springInterpolator)
				.setListener(null)
				.start()
		}
	}

	fun View.setupPressAnimation() {
		isClickable = true
		withAnimationCleanup {
			setOnTouchListener { v, event ->
				when (event.actionMasked) {
					android.view.MotionEvent.ACTION_DOWN -> v.animatePress()
					android.view.MotionEvent.ACTION_UP,
					android.view.MotionEvent.ACTION_CANCEL,
					-> v.animateRelease()
				}
				false
			}
		}
	}

	fun View.crossfadeWith(other: View, duration: Long = 300L) {
		withAnimationCleanup {
			other.withAnimationCleanup {
				other.alpha = 0f
				other.visibility = View.VISIBLE
				animate().alpha(0f).setDuration(duration).withEndAction {
					if (isAttachedToWindow) {
						visibility = View.GONE
					}
				}.start()
				other.animate().alpha(1f).setDuration(duration).setListener(null).start()
			}
		}
	}

	fun View.doOnLayoutSpring(action: (View) -> Unit) {
		doOnLayout { view ->
			view.withAnimationCleanup {
				view.alpha = 0f
				view.translationY = 24f
				action(view)
				view.animate()
					.alpha(1f)
					.translationY(0f)
					.setDuration(400)
					.setInterpolator(springInterpolator)
					.setListener(null)
					.start()
			}
		}
	}

	/**
	 * Cancels in-flight property animations and clears listeners that would retain this [View].
	 */
	fun View.cancelIosAnimations() {
		clearAnimation()
		animate().setListener(null)
		animate().cancel()
		resetIosAnimationState()
		setOnTouchListener(null)
	}

	/**
	 * Classical iOS Passcode error shake animation.
	 * Performs a rapid, damped horizontal oscillation with haptic feedback.
	 */
	fun View.shakeIos(onEnd: (() -> Unit)? = null) {
		withAnimationCleanup {
			val animator = ObjectAnimator.ofFloat(
				this,
				"translationX",
				0f, -24f, 22f, -18f, 14f, -8f, 4f, 0f,
			).apply {
				duration = 450L
				interpolator = DecelerateInterpolator()
				addListener(object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						translationX = 0f
						onEnd?.invoke()
					}
				})
			}
			animator.start()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				performHapticFeedback(HapticFeedbackConstants.REJECT)
			} else {
				performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
			}
		}
	}

	/**
	 * Animates an iOS PIN dot fill or unfill state with a crisp spring pulse.
	 */
	fun View.pulsePinDot(filled: Boolean) {
		withAnimationCleanup {
			if (filled) {
				scaleX = 0.5f
				scaleY = 0.5f
				animate()
					.scaleX(1f)
					.scaleY(1f)
					.setDuration(180L)
					.setInterpolator(springInterpolator)
					.setListener(null)
					.start()
			} else {
				animate()
					.scaleX(1f)
					.scaleY(1f)
					.setDuration(120L)
					.setInterpolator(easeOutInterpolator)
					.setListener(null)
					.start()
			}
		}
	}

	private fun View.withAnimationCleanup(block: View.() -> Unit) {
		ensureAnimationCleanupOnDetach()
		block()
	}

	private fun View.ensureAnimationCleanupOnDetach() {
		if (getTag(R.id.ios_animation_cleanup) != true) {
			setTag(R.id.ios_animation_cleanup, true)
			doOnDetach {
				setTag(R.id.ios_animation_cleanup, null)
				cancelIosAnimations()
			}
		}
	}

	private fun View.resetIosAnimationState() {
		alpha = 1f
		scaleX = 1f
		scaleY = 1f
	}
}
