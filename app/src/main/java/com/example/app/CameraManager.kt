package com.example.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CameraManager(
    private val activity: ComponentActivity,
    private val onPermissionGranted: () -> Unit
) : DefaultLifecycleObserver {

    private val requestPermissionLauncher: ActivityResultLauncher<String>

    init {
        // Đăng ký permission launcher (cách mới, sạch sẽ, không cần onRequestPermissionsResult)
        requestPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Toast.makeText(activity, "Quyền camera đã được cấp", Toast.LENGTH_SHORT).show()
                onPermissionGranted()
            } else {
                Toast.makeText(activity, "Cần quyền camera để sử dụng tính năng này", Toast.LENGTH_LONG).show()
            }
        }

        // Tự động kiểm tra khi Activity vào foreground
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        checkAndRequestCameraPermission()
    }

    fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Đã có quyền → gọi callback luôn
                onPermissionGranted()
            }

            activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Người dùng từng từ chối → giải thích
                Toast.makeText(
                    activity,
                    "Ứng dụng cần quyền camera để nhận diện biển báo giao thông",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> {
                // Lần đầu hoặc người dùng chọn "Don't ask again" → vẫn hỏi bình thường
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Gọi khi cần kiểm tra lại (ví dụ nhấn nút lần nữa)
    fun requestIfNeeded() {
        checkAndRequestCameraPermission()
    }
}