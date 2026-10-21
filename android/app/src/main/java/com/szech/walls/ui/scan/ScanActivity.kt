package com.szech.walls.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.Size
import com.szech.walls.R

/** 扫码 → 返回扫到的字符串（取消返回 null） */
class ScanContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, ScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(ScanActivity.EXTRA_RESULT) else null
}

/**
 * 扫码界面。没有用 zxing 自带的 CaptureActivity：那个界面是默认的取景框加一条不动的红线，
 * 提示字还压在框下面。这里自己拼，取景框、提示、往返红线都在 ScanOverlayView 里画。
 *
 * 加速这件事做了两层，都没牺牲解码格式：
 *   1. 把解码区域收到取景框那么大 —— 全帧一千来万个像素，收完只剩几十万，这一条最管用；
 *   2. 点屏幕手动对焦 —— 糊的帧解不出来，白解就是慢。
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "scan_result"
        const val EXTRA_FORMAT = "scan_format"
    }

    private lateinit var barcodeView: BarcodeView
    private lateinit var overlay: ScanOverlayView
    private var handled = false
    private var started = false
    private var torch = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                prepareAndStart()
            } else {
                Toast.makeText(this, "没有相机权限，扫不了码", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        barcodeView = findViewById(R.id.barcode_view)
        overlay = findViewById(R.id.scan_overlay)

        findViewById<ImageButton>(R.id.scan_close).setOnClickListener { finish() }
        val torchBtn = findViewById<ImageButton>(R.id.scan_torch)
        torchBtn.setOnClickListener {
            torch = !torch
            barcodeView.setTorch(torch)
            torchBtn.setColorFilter(if (torch) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt())
        }

        // 点一下屏幕：先对一次焦，对不上就白解，白解就是慢
        barcodeView.setOnClickListener { refocus() }

        // 全部条码格式都留着，不靠砍格式提速
        barcodeView.setDecoderFactory(DefaultDecoderFactory())

        if (ContextCompat.checkSelfPermission(this, MANIFEST_CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            prepareAndStart()
        } else {
            permissionLauncher.launch(MANIFEST_CAMERA)
        }
    }

    /** 等取景框量出尺寸，把解码区域收到跟它一样大，再开相机 */
    private fun prepareAndStart() {
        if (started) return
        barcodeView.post {
            if (isFinishing || isDestroyed) return@post
            val side = ScanOverlayView.frameSide(barcodeView.width, barcodeView.height)
            if (side > 0f) {
                barcodeView.setFramingRectSize(Size(side.toInt(), side.toInt()))
            }
            started = true
            barcodeView.decodeContinuous(BarcodeCallback { result -> onHit(result) })
            barcodeView.resume()
        }
    }

    private fun refocus() {
        try {
            barcodeView.changeCameraParameters { params ->
                val modes = params.supportedFocusModes ?: emptyList()
                when {
                    modes.contains("macro") -> params.focusMode = "macro"
                    modes.contains("auto") -> params.focusMode = "auto"
                }
                params
            }
        } catch (e: Exception) {
            // 对焦失败就随它去，不影响扫码
        }
    }

    private fun onHit(result: BarcodeResult) {
        if (handled) return
        handled = true
        barcodeView.stopDecoding()
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT, result.text)
                .putExtra(EXTRA_FORMAT, result.barcodeFormat?.name ?: "")
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (started) barcodeView.resume()
    }

    override fun onPause() {
        if (started) barcodeView.pause()
        super.onPause()
    }
}

private const val MANIFEST_CAMERA = Manifest.permission.CAMERA
