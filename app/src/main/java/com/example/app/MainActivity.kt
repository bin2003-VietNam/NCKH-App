package com.example.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import android.util.Log


class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var imageCapture: ImageCapture
    private lateinit var yolo: YoloManager
    private lateinit var textView: TextView
    private lateinit var tts: TextToSpeech
    private var history = History()
    private  val CAMERA_PERMISSION_REQUEST = 1001


    private val vietnamLabel = mapOf(
        "ben_xe_buyt" to "Bến xe buýt",
        "cam_di_nguoc_chieu" to "Cấm đi ngược chiều",
        "cam_do_xe" to "Cấm đỗ xe",
        "cam_dung_cam_do_xe" to "Cấm dừng và đỗ xe",
        "cam_queo_phai" to "Cấm quẹo phải",
        "cam_queo_trai" to "Cấm quẹo trái",
        "cam_xe_container" to "Cấm xe container",
        "cam_xe_o_to" to "Cấm xe ô tô",
        "cam_xe_tai" to "Cấm xe tải",
        "di_cham" to "Đi chậm",
        "duong_nguoi_di_bo_cat_ngang" to "Đường người đi bộ cắt ngang",
        "giao_nhau_voi_duong_khong_uu_tien" to "Giao nhau với đường không ưu tiên",
        "huong_phai_di_vong_chuong_ngai_vat" to "Hướng phải đi vòng chướng ngại vật",
        "toc_do_toi_da_cho_phep_50km" to "Tốc độ tối đa cho phép 50 km/h",
        "toc_do_toi_da_cho_phep_60km" to "Tốc độ tối đa cho phép 60 km/h",
        "tre_em" to "Trẻ em"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        textView = findViewById(R.id.textView)
        yolo = YoloManager(this)
        tts = TextToSpeech(this){status->
            if(status == TextToSpeech.SUCCESS){
                val result  = tts.setLanguage(Locale.getDefault())
                if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED){
                    Toast.makeText(this, "language is not supported", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }
    }

    val COOLDOWN_MS = 5_000L
    val REQUIRED_COUNT:Int = 5
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            // ImageAnalysis (YOLO)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)
//                val startTime = System.nanoTime()   // ⏱ bắt đầu
                val label_yolo = yolo.predictLabel(bitmap)
//                val endTime = System.nanoTime()     // ⏱ kết thúc
//                val inferTimeMs = (endTime - startTime) / 1_000_000.0
//                Log.d("time", inferTimeMs.toString())
                val label_text_string = vietnamLabel[label_yolo].toString()
                val current_time =  System.currentTimeMillis()
                runOnUiThread {
                    if (label_yolo.isNotEmpty()) {
                        textView.text = label_text_string + "  " + history.count.toString()

                        if(history.label !== label_text_string) {
                            history.label = label_text_string
                            history.count = 1
                            history.lastSpeech = current_time
                            tts.speak(label_text_string, TextToSpeech.QUEUE_FLUSH, null, null)
                        }else {
                            history.count++

                            if (
                                history.count >= REQUIRED_COUNT && current_time - history.lastSpeech >= COOLDOWN_MS
                            ) {
                                history.lastSpeech = current_time
                                history.count = 0
                                tts.speak(label_text_string, TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        }
                    } else {
                        textView.text = "Không phát hiện biển báo"
                    }
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
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }
    // Convert ImageProxy → Bitmap
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
        val matrix = android.graphics.Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)

        return bmp
    }
}

data class  History(
    var label: String = "",
    var count: Int = 0,
    var lastSpeech: Long = 0
)
