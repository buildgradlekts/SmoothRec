package com.smoothrec.app.encoder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renderizador OpenGL ES 2.0 ligero que inserta la marca de agua fija "SmoothRec"
 * directamente en la Surface de MediaCodec cuando la marca de agua está activada.
 *
 * Solo se instancia si el usuario activa la marca de agua; si está desactivada,
 * VirtualDisplay escribe directamente a MediaCodec con cero sobrecarga gráfica.
 */
class GlWatermarkRenderer(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
) {

    private val glThread = HandlerThread("SmoothRecGlWatermark", Process.THREAD_PRIORITY_DISPLAY).apply {
        start()
    }
    private val glHandler = Handler(glThread.looper)
    private val released = AtomicBoolean(false)
    @Volatile private var paused = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesProgram = 0
    private var oesTextureId = 0
    private var oesTexMatrixHandle = 0
    private var oesPosHandle = 0
    private var oesUvHandle = 0

    private var wmProgram = 0
    private var wmTextureId = 0
    private var wmPosHandle = 0
    private var wmUvHandle = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null

    private val stMatrix = FloatArray(16)
    private val fullQuadBuffer: FloatBuffer = createFloatBuffer(FULLSCREEN_QUAD)
    private lateinit var wmQuadBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer = createFloatBuffer(TEX_COORDS_UPRIGHT)
    private val wmTexCoordBuffer: FloatBuffer = createFloatBuffer(TEX_COORDS_BITMAP)

    fun start(): Surface {
        val ready = CountDownLatch(1)
        var initError: Throwable? = null

        glHandler.post {
            try {
                initEgl()
                initShadersAndTextures()
                val st = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ drawFrame() }, glHandler)
                }
                surfaceTexture = st
                captureSurface = Surface(st)
            } catch (t: Throwable) {
                initError = t
                cleanupInternal()
            } finally {
                ready.countDown()
            }
        }

        ready.await(3, TimeUnit.SECONDS)
        initError?.let { throw RuntimeException("No se pudo iniciar el renderizador de marca de agua", it) }
        return captureSurface ?: throw IllegalStateException("Capture surface no inicializada")
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val done = CountDownLatch(1)
        glHandler.post {
            cleanupInternal()
            done.countDown()
        }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        glThread.quitSafely()
        try {
            glThread.join(400)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun drawFrame() {
        if (released.get()) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "Error en updateTexImage", e)
            return
        }
        if (paused) {
            // Drenamos SurfaceTexture para no bloquear VirtualDisplay, sin enviar
            // fotogramas al encoder mientras la grabación está en pausa.
            return
        }

        st.getTransformMatrix(stMatrix)
        val rawTs = st.timestamp
        val ptsNs = if (rawTs > 0L) rawTs else System.nanoTime()

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 1. Fotograma de pantalla (OES)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, stMatrix, 0)
        drawQuad(oesPosHandle, oesUvHandle, fullQuadBuffer, texCoordBuffer)

        // 2. Marca de agua fija "SmoothRec" (textura estática pre-renderizada)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(wmProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTextureId)
        drawQuad(wmPosHandle, wmUvHandle, wmQuadBuffer, wmTexCoordBuffer)
        GLES20.glDisable(GLES20.GL_BLEND)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawQuad(posHandle: Int, uvHandle: Int, pos: FloatBuffer, uv: FloatBuffer) {
        pos.position(0)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize falló")
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            throw IllegalStateException("eglChooseConfig falló")
        }
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent falló")
        }
        Matrix.setIdentityM(stMatrix, 0)
    }

    private fun initShadersAndTextures() {
        oesProgram = buildProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
        oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPos")
        oesUvHandle = GLES20.glGetAttribLocation(oesProgram, "aUv")
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")

        wmProgram = buildProgram(WM_VERTEX_SHADER, WM_FRAGMENT_SHADER)
        wmPosHandle = GLES20.glGetAttribLocation(wmProgram, "aPos")
        wmUvHandle = GLES20.glGetAttribLocation(wmProgram, "aUv")

        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        oesTextureId = texIds[0]
        wmTextureId = texIds[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val watermarkBitmap = createWatermarkBitmap()
        val wmW = watermarkBitmap.width
        val wmH = watermarkBitmap.height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, watermarkBitmap, 0)
        watermarkBitmap.recycle()

        // Posición fija en esquina inferior derecha con margen seguro dentro de los límites.
        val shortSide = min(width, height).toFloat()
        val marginPx = max(12f, shortSide * 0.03f)
        val rightPx = (width - marginPx).coerceAtLeast(wmW.toFloat())
        val leftPx = (rightPx - wmW).coerceAtLeast(0f)
        val bottomPx = (height - marginPx).coerceAtLeast(wmH.toFloat())
        val topPx = (bottomPx - wmH).coerceAtLeast(0f)

        val ndcLeft = (leftPx / width) * 2f - 1f
        val ndcRight = (rightPx / width) * 2f - 1f
        val ndcTop = 1f - (topPx / height) * 2f
        val ndcBottom = 1f - (bottomPx / height) * 2f

        wmQuadBuffer = createFloatBuffer(
            floatArrayOf(
                ndcLeft, ndcBottom,
                ndcRight, ndcBottom,
                ndcLeft, ndcTop,
                ndcRight, ndcTop,
            ),
        )
    }

    private fun createWatermarkBitmap(): Bitmap {
        val shortSide = min(width, height).toFloat()
        val textSizePx = max(16f, shortSide * 0.025f)
        val pad = max(4f, textSizePx * 0.35f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(82, 255, 255, 255) // Blanco con transparencia elevada (~32%)
            textSize = textSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.04f
            setShadowLayer(2f, 0f, 1f, Color.argb(32, 0, 0, 0))
        }
        val textWidth = paint.measureText(WATERMARK_TEXT)
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val bmpW = (textWidth + pad * 2f).roundToInt().coerceAtLeast(16)
        val bmpH = (textHeight + pad * 2f).roundToInt().coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawText(WATERMARK_TEXT, pad, pad - fm.ascent, paint)
        return bitmap
    }

    private fun cleanupInternal() {
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            captureSurface?.release()
            surfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando SurfaceTexture", e)
        }
        captureSurface = null
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            val ids = intArrayOf(oesTextureId, wmTextureId).filter { it != 0 }.toIntArray()
            if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
            if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
            if (wmProgram != 0) GLES20.glDeleteProgram(wmProgram)

            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        oesTextureId = 0
        wmTextureId = 0
        oesProgram = 0
        wmProgram = 0
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
        }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .apply { position(0) }

    private companion object {
        const val TAG = "GlWatermarkRenderer"
        const val WATERMARK_TEXT = "SmoothRec"
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val FULLSCREEN_QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        val TEX_COORDS_UPRIGHT = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
        val TEX_COORDS_BITMAP = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )

        const val OES_VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * aUv).xy;
            }
        """

        const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vUv);
            }
        """

        const val WM_VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = aUv;
            }
        """

        const val WM_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vUv);
            }
        """
    }
}
