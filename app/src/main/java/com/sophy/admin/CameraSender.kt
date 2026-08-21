package com.sophy.admin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Captures the device camera and streams JPEG frames to Sophy Receiver as
 * chunked UDP packets (see VideoProtocol). Runs entirely on a background
 * thread so it keeps working from the foreground service even while the
 * Admin UI is closed, exactly like AudioSender does for the microphone.
 */
class CameraSender(
    private val context: Context,
    private val onStateChanged: () -> Unit
) {
    companion object {
        private const val TAG = "SophyCamera"
        private const val TARGET_FPS = 10
        private const val JPEG_QUALITY = 55
        private const val TARGET_PIXELS = 960L * 540L
        private const val MAX_PIXELS = 1280L * 720L
    }

    private val running = AtomicBoolean(false)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var orientationListener: OrientationEventListener? = null

    private var socket: DatagramSocket? = null
    @Volatile private var host: String = ""
    @Volatile private var port: Int = 45678

    private var sensorOrientation = 90
    private var facingFront = false
    @Volatile private var deviceRotationDegrees = 0

    private var frameId = 0
    private var lastFrameSentAt = 0L
    private val frameIntervalMs = 1000L / TARGET_FPS

    fun setTarget(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        AdminState.videoError = ""
        AdminState.videoStatus = "تشغيل الكاميرا…"
        onStateChanged()

        try {
            val udp = DatagramSocket().apply {
                trafficClass = 0x10
                sendBufferSize = 256 * 1024
            }
            socket = udp
        } catch (t: Throwable) {
            fail("تعذر فتح منفذ الشبكة: ${t.message}")
            return
        }

        backgroundThread = HandlerThread("SophyCameraBg").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        startOrientationListener()
        backgroundHandler?.post { openCamera() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        backgroundHandler?.post { closeCameraInternal() }
        try { orientationListener?.disable() } catch (_: Throwable) {}
        orientationListener = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(500) } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
        AdminState.videoBroadcasting.set(false)
        if (AdminState.videoError.isEmpty()) AdminState.videoStatus = "بث الكاميرا متوقف"
        onStateChanged()
    }

    private fun startOrientationListener() {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientationDegrees: Int) {
                if (orientationDegrees == ORIENTATION_UNKNOWN) return
                deviceRotationDegrees = (((orientationDegrees + 45) / 90) * 90) % 360
            }
        }
        orientationListener = listener
        if (listener.canDetectOrientation()) listener.enable()
    }

    private fun openCamera() {
        if (!running.get()) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager = manager
        try {
            val cameraId = pickCameraId(manager)
            if (cameraId == null) {
                fail("لا توجد كاميرا متاحة")
                return
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT

            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val size = chooseSize(sizes)
            if (size == null) {
                fail("تعذر اختيار دقة الكاميرا")
                return
            }

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r -> handleImage(r) }, backgroundHandler)
            imageReader = reader

            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: SecurityException) {
            fail("صلاحية الكاميرا مطلوبة")
        } catch (t: Throwable) {
            fail(t.message ?: "تعذر فتح الكاميرا")
        }
    }

    private fun pickCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        for (id in ids) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return ids.firstOrNull()
    }

    private fun chooseSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        val candidates = sizes.filter { it.width.toLong() * it.height <= MAX_PIXELS }
        val pool = if (candidates.isNotEmpty()) candidates else sizes.toList()
        return pool.minByOrNull { abs(it.width.toLong() * it.height - TARGET_PIXELS) }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            if (!running.get()) {
                camera.close()
                return
            }
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            fail("خطأ في الكاميرا ($error)")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val reader = imageReader ?: return
        try {
            val surface = reader.surface
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get()) {
                            session.close()
                            return
                        }
                        try {
                            captureSession = session
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            request.addTarget(surface)
                            session.setRepeatingRequest(request.build(), null, backgroundHandler)
                            AdminState.videoBroadcasting.set(true)
                            AdminState.videoStatus = "بث الكاميرا يعمل"
                            onStateChanged()
                        } catch (t: Throwable) {
                            fail(t.message ?: "تعذر بدء التقاط الكاميرا")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail("تعذر تهيئة جلسة الكاميرا")
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            fail(t.message ?: "تعذر إنشاء جلسة الكاميرا")
        }
    }

    private fun handleImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (t: Throwable) { null } ?: return
        try {
            if (!running.get()) return
            val now = System.currentTimeMillis()
            if (now - lastFrameSentAt < frameIntervalMs) return
            lastFrameSentAt = now

            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, baos)
            var jpeg = baos.toByteArray()

            val rotation = computeRotation()
            if (rotation != 0) jpeg = rotateJpeg(jpeg, rotation)

            sendFrame(jpeg)
        } catch (t: Throwable) {
            Log.e(TAG, "Frame handling failed", t)
        } finally {
            image.close()
        }
    }

    private fun computeRotation(): Int {
        return if (facingFront) {
            (sensorOrientation + deviceRotationDegrees) % 360
        } else {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        }
    }

    private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (rotated !== bitmap) rotated.recycle()
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Converts a Camera2 YUV_420_888 image into a tightly packed NV21 buffer. */
    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val nv21 = ByteArray(width * height + chromaWidth * chromaHeight * 2)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        var outPos = 0
        if (yPlane.pixelStride == 1) {
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(nv21, outPos, width)
                outPos += width
            }
        } else {
            val rowBytes = ByteArray(yPlane.rowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                val avail = min(yPlane.rowStride, yBuffer.remaining())
                yBuffer.get(rowBytes, 0, avail)
                var col = 0
                var idx = 0
                while (col < width) {
                    nv21[outPos++] = rowBytes[idx]
                    idx += yPlane.pixelStride
                    col++
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowBytes = ByteArray(uPlane.rowStride)
        val vRowBytes = ByteArray(vPlane.rowStride)
        for (row in 0 until chromaHeight) {
            uBuffer.position(row * uPlane.rowStride)
            uBuffer.get(uRowBytes, 0, min(uPlane.rowStride, uBuffer.remaining()))
            vBuffer.position(row * vPlane.rowStride)
            vBuffer.get(vRowBytes, 0, min(vPlane.rowStride, vBuffer.remaining()))
            var col = 0
            var uIdx = 0
            var vIdx = 0
            while (col < chromaWidth) {
                nv21[outPos++] = vRowBytes[vIdx]
                nv21[outPos++] = uRowBytes[uIdx]
                uIdx += uPlane.pixelStride
                vIdx += vPlane.pixelStride
                col++
            }
        }
        return nv21
    }

    private fun sendFrame(jpeg: ByteArray) {
        val udp = socket ?: return
        if (host.isEmpty()) return
        val address = try { InetAddress.getByName(host) } catch (t: Throwable) { return }

        val id = frameId++
        val ts = System.nanoTime() / 1_000L
        val totalChunks = ((jpeg.size + VideoProtocol.CHUNK_PAYLOAD - 1) / VideoProtocol.CHUNK_PAYLOAD)
            .coerceAtLeast(1)

        var offset = 0
        var chunkIndex = 0
        while (offset < jpeg.size) {
            val length = min(VideoProtocol.CHUNK_PAYLOAD, jpeg.size - offset)
            val packet = VideoProtocol.chunk(
                frameId = id,
                chunkIndex = chunkIndex,
                chunkCount = totalChunks,
                timestampUs = ts,
                width = 0,
                height = 0,
                data = jpeg,
                offset = offset,
                length = length
            )
            try {
                udp.send(DatagramPacket(packet, packet.size, address, port))
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "send failed", t)
                return
            }
            offset += length
            chunkIndex++
        }
        AdminState.videoFrames.incrementAndGet()
        AdminState.lastVideoFrameAt.set(System.currentTimeMillis())
    }

    private fun closeCameraInternal() {
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        AdminState.videoError = message
        AdminState.videoStatus = "خطأ في بث الكاميرا"
        running.set(false)
        backgroundHandler?.post { closeCameraInternal() }
        AdminState.videoBroadcasting.set(false)
        onStateChanged()
    }
}
