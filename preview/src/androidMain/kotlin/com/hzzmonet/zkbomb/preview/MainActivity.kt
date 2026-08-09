package com.hzzmonet.zkbomb.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

/**
 * Host activity for Bomb on Android.
 *
 * Deliberately thin: no permissions, no services, no privileged calls. The whole
 * point is to look at the UI at real density on real hardware — everything Bomb
 * actually does lives behind the AIDL surface described in docs/BOMB_PLAN.md and
 * is not part of this APK.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status and navigation bars — the backdrop should run
        // edge to edge, the way it will in the product.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            BombApp()
        }
    }
}
