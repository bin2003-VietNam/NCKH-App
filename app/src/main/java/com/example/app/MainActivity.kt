package com.example.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.text.intl.Locale
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private  var tts: TextToSpeech? = null
    private lateinit var previewView: PreviewView
    private lateinit var imageCapture: ImageCapture
    private lateinit var yolo: YoloManager
    private lateinit var textView: TextView

    private val labels = listOf(
        "ben_xe_buyt",
        "cam_di_nguoc_chieu",
        "cam_do_xe",
        "cam_dung_cam_do_xe",
        "cam_queo_phai",
        "cam_queo_trai",
        "cam_xe_container",
        "cam_xe_o_to",
        "cam_xe_tai",
        "di_cham",
        "duong_nguoi_di_bo_cat_ngang",
        "giao_nhau_voi_duong_khong_uu_tien",
        "huong_phai_di_vong_chuong_ngai_vat",
        "toc_do_toi_da_cho_phep_50km",
        "toc_do_toi_da_cho_phep_60km",
        "tre_em"
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        textView = findViewById(R.id.textView)
        tts = TextToSpeech(this, this)
        yolo = YoloManager(this)

        startCamera()
    }

     fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts!!.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language not supported!")
            } else {

            }
        }
    }
    private fun speakOut(text: String) {
        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null,"")
    }

    public override fun onDestroy() {
        // Shutdown TTS when
        // activity is destroyed
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        super.onDestroy()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // ----------------------
            // ImageAnalysis (YOLO)
            // ----------------------
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)

                // Inference chạy ở background → tốt
                val detections = yolo.predict(bitmap)
                Log.d("DEBUG", detections.toString())

                // === TẤT CẢ CẬP NHẬT UI PHẢI VỀ MAIN THREAD ===
                runOnUiThread {
                    if (detections.isNotEmpty()) {
                        // Lấy biển báo có độ tin cậy cao nhất
                        val best = detections.maxByOrNull { it.score }!!
                        textView.text = labels[best.cls]
                        // Ví dụ hiển thị: "Cấm đỗ & dừng xe"
                    } else {
                        textView.text = "Không phát hiện biển báo"
                    }

                    // Cập nhật khung vẽ (nếu bạn có overlay)
                }

                imageProxy.close()
            }

            // Chụp ảnh
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()

            // 🔥 QUAN TRỌNG: phải bind imageAnalysis vào camera
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis, imageCapture
            )


        }, ContextCompat.getMainExecutor(this))
    }

    // ----------------------------
    // Convert ImageProxy → Bitmap
    // ----------------------------
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()

        var bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        // 🔥 Fix crash do xoay ảnh
        val matrix = android.graphics.Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)

        return bmp
    }
}
