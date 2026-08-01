package com.cleo.blebridge

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.cleo.blebridge.databinding.FragmentUsbCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Legge una webcam USB (UVC) collegata al tablet via adattatore USB-C -> USB-A,
 * ne estrae i fotogrammi grezzi, applica OCR sulla zona selezionata e trasmette
 * la velocità letta via BLE, con la stessa logica anti-rumore della fotocamera integrata.
 *
 * NOTA: questa parte usa una libreria di terze parti (AndroidUSBCamera) che gestisce
 * codice nativo; è più delicata delle altre modalità e potrebbe richiedere qualche
 * aggiustamento se qualcosa non combacia esattamente con l'ambiente del tablet.
 */
class UsbCameraFragment : CameraFragment() {

    private var mViewBinding: FragmentUsbCameraBinding? = null
    private lateinit var peripheral: CscPeripheral
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var analysisBusy = false

    private var lastGoodSpeed: Double? = null
    private var pendingCandidate: Double? = null
    private var pendingCount = 0

    private val previewWidth = 1280
    private val previewHeight = 720

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        if (mViewBinding == null) {
            mViewBinding = FragmentUsbCameraBinding.inflate(inflater, container, false)
        }
        return mViewBinding!!.root
    }

    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(requireContext())
    }

    override fun getCameraViewContainer(): ViewGroup {
        return mViewBinding!!.cameraViewContainer
    }

    override fun getGravity(): Int = Gravity.CENTER

    override fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        return CameraUVC(ctx, device)
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(previewWidth)
            .setPreviewHeight(previewHeight)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setRawPreviewData(true)
            .create()
    }

    override fun onCameraState(self: com.jiangdg.ausbc.MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        val binding = mViewBinding ?: return
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                binding.textUsbStatus.text = "✅ Webcam USB collegata"
                addPreviewDataCallBack(object : IPreviewDataCallBack {
                    override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
                        handleFrame(data, width, height, format)
                    }
                })
            }
            ICameraStateCallBack.State.CLOSED -> {
                binding.textUsbStatus.text = "🔌 Webcam USB scollegata"
            }
            ICameraStateCallBack.State.ERROR -> {
                binding.textUsbStatus.text = "⚠️ Errore webcam USB: $msg"
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = mViewBinding ?: return

        peripheral = CscPeripheral(
            context = requireContext(),
            onStatus = { status -> requireActivity().runOnUiThread { binding.textBleStatus.text = status } },
            onLog = { }
        )

        binding.buttonDetectUsb.setOnClickListener { requestUsbPermissionManually() }

        binding.buttonResetRoi.setOnClickListener {
            binding.roiOverlay.reset()
            lastGoodSpeed = null
            pendingCandidate = null
            pendingCount = 0
        }

        binding.buttonStart.setOnClickListener {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(requireContext(), "Attiva il Bluetooth e riprova", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            peripheral.start()
        }
        binding.buttonStop.setOnClickListener { peripheral.stop() }
    }

    private fun handleFrame(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
        if (data == null || analysisBusy) return
        analysisBusy = true

        try {
            val bitmap = when (format) {
                IPreviewDataCallBack.DataFormat.NV21 -> nv21ToBitmap(data, width, height)
                else -> null
            }
            if (bitmap == null) {
                analysisBusy = false
                return
            }

            val roi = mViewBinding?.roiOverlay?.normalizedRect
            val bitmapToAnalyze = if (roi != null) {
                val left = (roi.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 2)
                val top = (roi.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 2)
                val w = (roi.width() * bitmap.width).toInt().coerceAtLeast(1).coerceAtMost(bitmap.width - left)
                val h = (roi.height() * bitmap.height).toInt().coerceAtLeast(1).coerceAtMost(bitmap.height - top)
                Bitmap.createBitmap(bitmap, left, top, w, h)
            } else {
                bitmap
            }

            val image = InputImage.fromBitmap(bitmapToAnalyze, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val match = Regex("""\d+[.,]?\d*""").find(visionText.text)
                    val raw = match?.value?.replace(',', '.')?.toDoubleOrNull()
                    handleDetectedValue(raw)
                }
                .addOnCompleteListener { analysisBusy = false }
        } catch (e: Exception) {
            analysisBusy = false
        }
    }

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val jpegBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun handleDetectedValue(raw: Double?) {
        if (raw == null) return

        var candidate = raw
        if (candidate > 60.0 && candidate % 10.0 == 0.0) {
            val adjusted = candidate / 10.0
            if (adjusted in 0.0..60.0) candidate = adjusted
        }
        if (candidate !in 0.0..80.0) return

        val last = lastGoodSpeed
        if (last == null || abs(candidate - last) <= 5.0) {
            acceptSpeed(candidate)
        } else {
            if (pendingCandidate != null && abs(candidate - pendingCandidate!!) <= 2.0) {
                pendingCount++
            } else {
                pendingCandidate = candidate
                pendingCount = 1
            }
            if (pendingCount >= 2) {
                acceptSpeed(candidate)
            }
        }
    }

    private fun acceptSpeed(v: Double) {
        lastGoodSpeed = v
        pendingCandidate = null
        pendingCount = 0
        requireActivity().runOnUiThread { mViewBinding?.textDetectedSpeed?.text = "Velocità rilevata: $v km/h" }
        peripheral.currentSpeedKmh = v
    }

    private val usbPermissionAction = "com.cleo.blebridge.USB_PERMISSION"

    private val usbPermissionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (usbPermissionAction == intent.action) {
                val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                requireActivity().runOnUiThread {
                    if (granted) {
                        Toast.makeText(requireContext(), "Permesso concesso! Riprova a collegare la webcam se il video non parte da solo.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "Permesso negato dall'utente o dal sistema.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun requestUsbPermissionManually() {
        val usbManager = requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Il sistema Android non vede NESSUN dispositivo USB collegato in questo momento.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val filter = android.content.IntentFilter(usbPermissionAction)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(usbPermissionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(usbPermissionReceiver, filter)
        }

        for (device in deviceList.values) {
            Toast.makeText(requireContext(), "Trovato dispositivo: ${device.deviceName} (${device.manufacturerName ?: "?"})", Toast.LENGTH_LONG).show()
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = android.app.PendingIntent.getBroadcast(
                requireContext(), 0, android.content.Intent(usbPermissionAction), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        peripheral.stop()
        try { requireContext().unregisterReceiver(usbPermissionReceiver) } catch (e: Exception) { }
        mViewBinding = null
    }
}
