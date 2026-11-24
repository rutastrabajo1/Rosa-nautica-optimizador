package com.example.rutaoptimizador

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView

/** Pantalla de bienvenida con video de AlAl Rosa Náutica Optimizador. */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var videoView: VideoView? = null

    private val openMain = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bgColor = Color.rgb(4, 25, 48)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val root = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
        }

        val vv = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            val uri = Uri.parse("android.resource://$packageName/${R.raw.splash_rosa_nautica}")
            setVideoURI(uri)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                start()
            }
            setOnCompletionListener {
                handler.removeCallbacks(openMain)
                handler.post(openMain)
            }
        }

        root.addView(vv)
        setContentView(root)
        videoView = vv

        handler.postDelayed(openMain, 3200L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openMain)
        videoView?.stopPlayback()
        videoView = null
        super.onDestroy()
    }
}