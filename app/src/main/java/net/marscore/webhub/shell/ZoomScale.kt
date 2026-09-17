package net.marscore.webhub.shell

/**
 * Fixed-zoom scale math (task 9.4).
 *
 * `WebView.setInitialScale(percent)` interprets the percent against **physical** pixels, bypassing
 * density normalization — so a user-facing "100%" renders ~density× too small on high-density
 * devices (e.g. 100% looks tiny on a density≈2.5 phone; ~250% looks like the system default).
 *
 * To make "100%" mean "the system default rendering ratio" regardless of device density, we multiply
 * the user's percent by the device density before handing it to `setInitialScale`.
 *
 * Kept as a pure, Android-free top-level function so it is unit-testable on a plain JVM.
 */

/**
 * @param percent  the user-facing zoom percent (e.g. 100 for 100%, 75 for 75%).
 * @param density  the device's `displayMetrics.density` (e.g. 2.5, 2.0, 1.0).
 * @return the density-adjusted scale to pass to `WebView.setInitialScale`, clamped to [1, 1000]
 *         (the platform's effective working range; values outside are either no-ops or absurd).
 */
internal fun effectiveInitialScale(percent: Int, density: Float): Int =
    (percent * density).toInt().coerceIn(1, 1000)