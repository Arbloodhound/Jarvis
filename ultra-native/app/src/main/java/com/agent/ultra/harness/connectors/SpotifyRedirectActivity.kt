package com.agent.ultra.harness.connectors

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives Spotify's redirect ([SpotifyAuth.REDIRECT]), finishes the sign-in, says how it went, closes. */
class SpotifyRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) { finish(); return }
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val line = try { SpotifyAuth.finish(app, data) } catch (e: Exception) { "Spotify sign-in failed: ${e.message}" }
            Toast.makeText(app, line, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
