package com.hermesandroid.bridge.robot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobotFaceViewTest {
    @Test
    fun `cinematic face renders every robot phase without a blank frame`() {
        val view = RobotFaceView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 720, 900)
        val output = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        RobotPhase.entries.forEach { phase ->
            view.phase = phase
            view.draw(canvas)
            assertEquals("Cradata Roboter-Gesicht: ${phase.wireName}", view.contentDescription)
            assertNotEquals(Color.TRANSPARENT, output.getPixel(360, 450))
        }
    }
}
