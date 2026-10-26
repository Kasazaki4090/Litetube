package com.hhst.youtubelite.extractor

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView

class ExtractionTestActivity : Activity() {
	lateinit var surface: SurfaceView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		surface = SurfaceView(this)
		setContentView(surface)
	}
}
