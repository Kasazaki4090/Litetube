package com.hhst.youtubelite.util;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Utility methods for View-related operations like DP/PX conversion and animations.
 */
public final class ViewUtils {

	private static final float ALPHA_VISIBLE = 1.0f;
	private static final float ALPHA_INVISIBLE = 0.0f;

	/**
	 * Converts DP to pixels.
	 */
	public static int dpToPx(@NonNull Context context, float dp) {
		return (int) (dp * context.getResources().getDisplayMetrics().density);
	}

	/**
	 * Gets the screen width in pixels.
	 */
	public static int getScreenWidth(@NonNull Context context) {
		return context.getResources().getDisplayMetrics().widthPixels;
	}

	/**
	 * Animates a view's alpha.
	 */
	public static void animateViewAlpha(@NonNull View v, float alpha, int visibilityIfGone) {
		if (Float.compare(alpha, ALPHA_VISIBLE) == 0) {
			v.animate().cancel();
			v.setAlpha(ALPHA_VISIBLE);
			v.setVisibility(View.VISIBLE);
		} else if (v.getVisibility() != View.VISIBLE) {
			v.setAlpha(ALPHA_INVISIBLE);
			v.setVisibility(visibilityIfGone);
		} else {
			v.setVisibility(View.VISIBLE);
			v.animate().alpha(alpha).setDuration(100L).withEndAction(() -> {
				if (Float.compare(alpha, ALPHA_INVISIBLE) == 0) {
					v.setVisibility(visibilityIfGone);
				}
			}).start();
		}
	}

	/**
	 * Sets the system UI visibility for fullscreen mode.
	 *
	 * <p>Uses the modern {@link WindowInsetsControllerCompat} API (the same one used by
	 * edge-to-edge) instead of the deprecated {@code setSystemUiVisibility} flags. Mixing the
	 * legacy flags with edge-to-edge made the status bar opaque and resized/squashed content
	 * when swiping down to reveal the bars.
	 *
	 * @param activity   The activity whose window controls are being changed.
	 * @param fullscreen True to enter fullscreen, false to exit.
	 */
	public static void setFullscreen(@NonNull Activity activity, boolean fullscreen) {
		setFullscreen(activity.getWindow(), fullscreen);
	}

	/**
	 * Sets the system UI visibility for fullscreen mode on a specific window.
	 *
	 * @param window     The window whose controls are being changed.
	 * @param fullscreen True to enter fullscreen, false to exit.
	 */
	public static void setFullscreen(@NonNull Window window, boolean fullscreen) {
		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
						window, window.getDecorView());
		if (fullscreen) {
			controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
			controller.setSystemBarsBehavior(
							WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		} else {
			controller.show(WindowInsetsCompat.Type.systemBars());
			controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
		}
	}

	/**
	 * Shows a dialog without bringing back the status bar if the activity is in fullscreen.
	 */
	public static void showFullscreenDialog(@NonNull Dialog dialog, boolean fullscreen) {
		Window window = dialog.getWindow();
		if (fullscreen && window != null) {
			window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
							WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
		}
		dialog.show();
		if (fullscreen && window != null) {
			setFullscreen(window, true);
			window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
		}
	}
}
