package com.example.rutaoptimizador

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.min

/** Pantalla de bienvenida animada de AlAl Rosa Náutica Optimizador. */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openMain = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(4, 25, 48)
        window.navigationBarColor = Color.rgb(4, 25, 48)
        setContentView(AnimatedGifView(this))
        handler.postDelayed(openMain, 3200L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openMain)
        super.onDestroy()
    }
}

private class AnimatedGifView(activity: Activity) : View(activity) {
    private val movie: Movie? = activity.assets.open("splash_rosa_nautica.gif").use {
        Movie.decodeStream(it)
    }
    private val startedAt = android.os.SystemClock.uptimeMillis()

    init {
        setBackgroundColor(Color.rgb(4, 25, 48))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gif = movie ?: return
        val duration = if (gif.duration() > 0) gif.duration() else 1000
        gif.setTime(((android.os.SystemClock.uptimeMillis() - startedAt).toInt()) % duration)
        val scale = min(width.toFloat() / gif.width().coerceAtLeast(1),
                        height.toFloat() / gif.height().coerceAtLeast(1))
        val drawWidth = gif.width() * scale
        val drawHeight = gif.height() * scale
        canvas.save()
        canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
        canvas.scale(scale, scale)
        gif.draw(canvas, 0f, 0f)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
