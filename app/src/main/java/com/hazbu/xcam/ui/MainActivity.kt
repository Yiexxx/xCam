package com.hazbu.xcam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants.KEY_ENABLE_CAPTURE_NOTIF
import com.hazbu.xcam.data.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_APP
import com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE
import com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME
import com.hazbu.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.data.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.data.Constants.PREFS_NAME
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val VIRTUAL_PREFIX = "virtual."
        private const val DEFAULT_VIDEO_NAME = "virtual.mp4"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var previewStack: FrameLayout
    private val previewCards = mutableListOf<ImageView>()
    private lateinit var tvNoPreview: TextView
    private lateinit var tvMediaCounter: TextView
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnSelectVideo: MaterialButton
    private lateinit var tvModuleStatus: TextView
    private lateinit var cardModuleStatus: com.google.android.material.card.MaterialCardView

    private lateinit var switchCaptureNotif: MaterialSwitch
    private lateinit var tvPipelineEmpty: TextView
    private lateinit var layoutPipelineDetails: LinearLayout
    private lateinit var tvPipelineApp: TextView
    private lateinit var tvPipelineRoute: TextView
    private lateinit var tvPipelineTime: TextView
    private lateinit var btnCopyPipeline: MaterialButton
    private lateinit var btnClearPipeline: MaterialButton

    private var isMirrored = false
    private var rotationAngle = 0
    private var mediaPaths: MutableList<String> = mutableListOf()
    private var currentMediaIndex = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var mXposedService: XposedService? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, getString(R.string.toast_notification_permission), Toast.LENGTH_SHORT).show()
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val selectedUris = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let { selectedUris.add(it) }
                }
            }
            data?.data?.let { if (!selectedUris.contains(it)) selectedUris.add(it) }
            if (selectedUris.isNotEmpty()) importSelectedMedia(selectedUris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        XposedServiceHelper.registerListener(this)
        setupWindowInsets()
        setupUI()
        loadSettings()
        updateModuleStatusUI()
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateModuleStatusUI()
    }

    override fun onServiceBind(service: XposedService) {
        mXposedService = service
        runOnUiThread { updateModuleStatusUI() }
    }

    override fun onServiceDied(service: XposedService) {
        mXposedService = null
        runOnUiThread { updateModuleStatusUI() }
    }

    private fun updateModuleStatusUI() {
        val service = mXposedService
        val isActive = service != null || checkSelfActive()

        if (isActive) {
            val statusText = if (service != null && !service.frameworkName.isNullOrBlank()) {
                "${getString(R.string.status_module_active)} (${service.frameworkName})"
            } else {
                getString(R.string.status_module_active)
            }
            tvModuleStatus.text = statusText
            val activeColor = MaterialColors.getColor(tvModuleStatus, androidx.appcompat.R.attr.colorPrimary)
            tvModuleStatus.setTextColor(activeColor)
            cardModuleStatus.strokeColor = activeColor
        } else {
            tvModuleStatus.text = getString(R.string.status_module_inactive)
            val inactiveColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val strokeColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOutline)
            tvModuleStatus.setTextColor(inactiveColor)
            cardModuleStatus.strokeColor = strokeColor
        }
    }

    private fun setupWindowInsets() {
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val basePadding = (24 * density).toInt()
            v.setPadding(
                systemBars.left + basePadding,
                systemBars.top + basePadding,
                systemBars.right + basePadding,
                systemBars.bottom + basePadding
            )
            insets
        }
    }

    private fun setupUI() {
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        setupTitleSpannable(tvTitle)
        previewStack = findViewById(R.id.preview_stack)
        setupPreviewCards()
        tvNoPreview = findViewById(R.id.tv_no_preview)
        tvMediaCounter = findViewById(R.id.tv_media_counter)
        btnDeleteMedia = findViewById(R.id.btn_delete_media)
        btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left)
        btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnSelectVideo = findViewById(R.id.btn_select_video)
        tvModuleStatus = findViewById(R.id.tv_module_status)
        cardModuleStatus = findViewById(R.id.card_module_status)

        switchCaptureNotif = findViewById(R.id.switch_capture_notif)
        tvPipelineEmpty = findViewById(R.id.tv_pipeline_empty)
        layoutPipelineDetails = findViewById(R.id.layout_pipeline_details)
        tvPipelineApp = findViewById(R.id.tv_pipeline_app)
        tvPipelineRoute = findViewById(R.id.tv_pipeline_route)
        tvPipelineTime = findViewById(R.id.tv_pipeline_time)
        btnCopyPipeline = findViewById(R.id.btn_copy_pipeline)
        btnClearPipeline = findViewById(R.id.btn_clear_pipeline)

        switchCaptureNotif.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(KEY_ENABLE_CAPTURE_NOTIF, isChecked)
            }
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        btnCopyPipeline.setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val app = prefs.getString(KEY_LATEST_PIPELINE_APP, "") ?: ""
            val route = prefs.getString(KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
            val time = prefs.getLong(KEY_LATEST_PIPELINE_TIME, 0L)

            if (app.isEmpty() && route.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_no_route_to_copy), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val formattedTime = if (time > 0L) {
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(time))
            } else ""

            val copyText = buildString {
                if (app.isNotEmpty()) append("目标应用：$app\n")
                if (route.isNotEmpty()) append("拦截路径：$route\n")
                if (formattedTime.isNotEmpty()) append("时间：$formattedTime")
            }.trim()

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.clipboard_pipeline), copyText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_pipeline_copied), Toast.LENGTH_SHORT).show()
        }

        btnClearPipeline.setOnClickListener {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                remove(KEY_LATEST_PIPELINE_APP)
                remove(KEY_LATEST_PIPELINE_ROUTE)
                remove(KEY_LATEST_PIPELINE_TIME)
            }
            loadTelemetryUI()
            Toast.makeText(this, getString(R.string.toast_pipeline_cleared), Toast.LENGTH_SHORT).show()
        }

        btnMirror.setOnClickListener {
            isMirrored = !isMirrored
            saveSettings()
            updatePreviewTransform()
        }
        btnRotateLeft.setOnClickListener {
            rotationAngle = (rotationAngle - 90 + 360) % 360
            saveSettings()
            updatePreviewTransform()
        }
        btnRotateRight.setOnClickListener {
            rotationAngle = (rotationAngle + 90) % 360
            saveSettings()
            updatePreviewTransform()
        }
        btnDeleteMedia.setOnClickListener { deleteMedia() }
        btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            videoPickerLauncher.launch(intent)
        }
    }

    private fun importSelectedMedia(uris: List<Uri>) {
        val mimeTypes = uris.map { contentResolver.getType(it) ?: "" }
        val hasVideo = mimeTypes.any { it.startsWith("video/") }
        if (hasVideo && uris.size > 1) {
            Toast.makeText(this, R.string.toast_select_photos_or_one_video, Toast.LENGTH_LONG).show()
            return
        }

        filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }
        mediaPaths.clear()
        currentMediaIndex = 0

        if (hasVideo) {
            copyVideoToInternal(uris.first(), mimeTypes.first())
        } else {
            Toast.makeText(this, R.string.toast_converting_image, Toast.LENGTH_SHORT).show()
            convertSelectedImages(uris, 0, mutableListOf())
        }
    }

    private fun copyVideoToInternal(uri: Uri, mimeType: String) {
        try {
            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType) ?: "mp4"
            val internalFile = File(filesDir, VIRTUAL_PREFIX + extension)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(internalFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选视频")
            saveMediaPath(internalFile.absolutePath)
            Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_media_import_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun convertSelectedImages(uris: List<Uri>, index: Int, convertedPaths: MutableList<String>) {
        if (index >= uris.size) {
            saveMediaPaths(convertedPaths)
            Toast.makeText(this, getString(R.string.toast_photos_imported, convertedPaths.size), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = uris[index]
            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            if (!mimeType.startsWith("image/")) throw IllegalArgumentException("仅支持选择照片")
            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType) ?: "jpg"
            val imageFile = File(filesDir, "${VIRTUAL_PREFIX}image_${index}.${extension}")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(imageFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取第 ${index + 1} 张照片")

            val outputVideo = File(filesDir, "${VIRTUAL_PREFIX}${index}.mp4")
            com.hazbu.xcam.utils.MediaConverter.convertImageToMp4(
                this, Uri.fromFile(imageFile), outputVideo
            ) { success ->
                runOnUiThread {
                    imageFile.delete()
                    if (success) {
                        convertedPaths.add(outputVideo.absolutePath)
                        convertSelectedImages(uris, index + 1, convertedPaths)
                    } else {
                        convertedPaths.forEach { File(it).delete() }
                        filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }
                        Toast.makeText(this, R.string.toast_conversion_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }
            Toast.makeText(this, getString(R.string.toast_media_import_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mediaPath = prefs.getString(KEY_MEDIA_PATH, "") ?: ""
        val savedPaths = prefs.getString("media_paths", null)
        mediaPaths = try {
            if (savedPaths.isNullOrBlank()) mutableListOf()
            else JSONArray(savedPaths).let { array ->
                MutableList(array.length()) { array.getString(it) }.filter { File(it).exists() }.toMutableList()
            }
        } catch (_: Exception) { mutableListOf() }
        currentMediaIndex = prefs.getInt("current_media_index", 0).coerceIn(0, (mediaPaths.size - 1).coerceAtLeast(0))
        isMirrored = prefs.getBoolean(KEY_IS_MIRRORED, false)
        rotationAngle = prefs.getInt(KEY_ROTATION_ANGLE, 0)
        switchCaptureNotif.isChecked = prefs.getBoolean(KEY_ENABLE_CAPTURE_NOTIF, true)
        updatePreview(mediaPaths.getOrNull(currentMediaIndex) ?: mediaPath)
        updateMediaCounter()
        updatePreviewTransform()
        loadTelemetryUI()
    }

    private fun loadTelemetryUI() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val app = prefs.getString(KEY_LATEST_PIPELINE_APP, "") ?: ""
        val route = prefs.getString(KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
        val time = prefs.getLong(KEY_LATEST_PIPELINE_TIME, 0L)

        if (app.isEmpty() || route.isEmpty()) {
            tvPipelineEmpty.visibility = View.VISIBLE
            layoutPipelineDetails.visibility = View.GONE
        } else {
            tvPipelineEmpty.visibility = View.GONE
            layoutPipelineDetails.visibility = View.VISIBLE
            tvPipelineApp.text = getString(R.string.label_last_active_app, app)
            tvPipelineRoute.text = route
            val formatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
            tvPipelineTime.text = getString(R.string.label_last_timestamp, formatted)
        }
    }

    private fun updatePreviewTransform() {
        ivPreview.scaleX = if (isMirrored) -1f else 1f
        ivPreview.rotation = rotationAngle.toFloat()
        val activeColor = MaterialColors.getColor(btnMirror, androidx.appcompat.R.attr.colorPrimary)
        val activeIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE)
        val inactiveColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.LTGRAY)
        val inactiveIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.DKGRAY)
        btnMirror.setBackgroundColor(if (isMirrored) activeColor else inactiveColor)
        btnMirror.iconTint = android.content.res.ColorStateList.valueOf(if (isMirrored) activeIconColor else inactiveIconColor)
    }

    private fun setupPreviewCards() {
        previewCards.clear()
        val cardColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
        fun createCard(elevationDp: Float): ImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 12f * resources.displayMetrics.density
                setStroke(
                    (2f * resources.displayMetrics.density).toInt(),
                    MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOutline, Color.LTGRAY)
                )
            }
            setPadding(
                (3f * resources.displayMetrics.density).toInt(),
                (3f * resources.displayMetrics.density).toInt(),
                (3f * resources.displayMetrics.density).toInt(),
                (3f * resources.displayMetrics.density).toInt()
            )
            clipToOutline = true
            elevation = elevationDp * resources.displayMetrics.density
            isClickable = true
            setOnTouchListener { view, event ->
                val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
                val swipeThreshold = 48f * resources.displayMetrics.density
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = event.x
                        touchStartY = event.y
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - touchStartX
                        val dy = event.y - touchStartY
                        if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener false
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - touchStartX
                        val dy = event.y - touchStartY
                        if (mediaPaths.size > 1 && kotlin.math.abs(dx) >= swipeThreshold &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            val direction = if (dx < 0) 1 else -1
                            animateMediaTo(currentMediaIndex + direction, direction)
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> true
                }
            }
        }
        val backTwo = createCard(2f)
        val backOne = createCard(4f)
        val front = createCard(6f)
        previewCards.addAll(listOf(front, backOne, backTwo))
        previewStack.removeAllViews()
        previewStack.addView(backTwo)
        previewStack.addView(backOne)
        previewStack.addView(front)
        ivPreview = front
        resetPreviewCardPositions()
    }

    private fun resetPreviewCardPositions() {
        val density = resources.displayMetrics.density
        previewCards.forEachIndexed { index, card ->
            card.animate().cancel()
            val inset = (22f - index * 11f) * density
            val params = card.layoutParams as FrameLayout.LayoutParams
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.setMargins(inset.toInt(), inset.toInt(), inset.toInt(), inset.toInt())
            card.layoutParams = params
            card.translationX = 0f
            card.translationY = 0f
            card.scaleX = 1f
            card.scaleY = 1f
            card.rotation = 0f
            card.alpha = 1f
            card.elevation = (8f - index * 3f) * density
        }
        // Keep the active photo on top; the next two photos remain visibly exposed around it.
        previewStack.removeAllViews()
        previewStack.addView(previewCards[2])
        previewStack.addView(previewCards[1])
        previewStack.addView(previewCards[0])
    }

    private fun setPreviewImage(imageView: ImageView, path: String): Boolean {
        return try {
            if (path.endsWith(".mp4", ignoreCase = true)) {
                val retriever = MediaMetadataRetriever()
                val bitmap = try {
                    retriever.setDataSource(path)
                    retriever.getFrameAtTime(1_000_000)
                } finally {
                    retriever.release()
                }
                imageView.setImageBitmap(bitmap)
                bitmap != null
            } else {
                val bitmap = BitmapFactory.decodeFile(path)
                imageView.setImageBitmap(bitmap)
                bitmap != null
            }
        } catch (_: Exception) {
            imageView.setImageDrawable(null)
            false
        }
    }

    private fun updatePreview(path: String) {
        val valid = path.isNotEmpty() && File(path).exists() && setPreviewImage(ivPreview, path)
        if (!valid) {
            previewCards.forEach { it.setImageDrawable(null) }
            tvNoPreview.visibility = View.VISIBLE
            btnDeleteMedia.visibility = View.GONE
            previewCards.drop(1).forEach { it.visibility = View.GONE }
            resetPreviewCardPositions()
            return
        }

        tvNoPreview.visibility = View.GONE
        btnDeleteMedia.visibility = View.VISIBLE
        previewCards.drop(1).forEachIndexed { offset, card ->
            val mediaIndex = currentMediaIndex + offset + 1
            val nextPath = mediaPaths.getOrNull(mediaIndex)
            if (mediaPaths.size > 1 && nextPath != null && File(nextPath).exists() &&
                setPreviewImage(card, nextPath)) {
                card.visibility = View.VISIBLE
            } else {
                card.setImageDrawable(null)
                card.visibility = View.GONE
            }
        }
        resetPreviewCardPositions()
    }

    private fun animateMediaTo(index: Int, direction: Int) {
        if (index !in mediaPaths.indices || previewCards.size < 2) return
        val outgoing = previewCards[0]
        val incoming = previewCards[1]
        if (!setPreviewImage(incoming, mediaPaths[index])) return

        val density = resources.displayMetrics.density
        incoming.visibility = View.VISIBLE
        incoming.translationX = direction * 26f * density
        incoming.translationY = 8f * density
        incoming.scaleX = 0.94f
        incoming.scaleY = 0.94f
        incoming.bringToFront()

        incoming.animate()
            .translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()

        outgoing.animate()
            .translationX(-direction * (outgoing.width + 40f * density))
            .rotation(-direction * 7f)
            .alpha(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                previewCards.remove(incoming)
                previewCards.add(0, incoming)
                ivPreview = incoming
                showMediaAt(index)
            }
            .start()
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
    }

    private fun saveMediaPath(path: String) {
        isMirrored = false
        rotationAngle = 0
        mediaPaths = if (path.isBlank()) mutableListOf() else mutableListOf(path)
        currentMediaIndex = 0
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MEDIA_PATH, path)
            putString("media_paths", JSONArray(mediaPaths).toString())
            putInt("current_media_index", currentMediaIndex)
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
        updatePreview(path)
        updateMediaCounter()
        updatePreviewTransform()
    }

    private fun saveMediaPaths(paths: List<String>) {
        mediaPaths = paths.toMutableList()
        currentMediaIndex = 0
        isMirrored = false
        rotationAngle = 0
        val activePath = mediaPaths.firstOrNull().orEmpty()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MEDIA_PATH, activePath)
            putString("media_paths", JSONArray(mediaPaths).toString())
            putInt("current_media_index", currentMediaIndex)
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
        updatePreview(activePath)
        updateMediaCounter()
        updatePreviewTransform()
    }

    private fun showMediaAt(index: Int) {
        if (index !in mediaPaths.indices) return
        currentMediaIndex = index
        val path = mediaPaths[index]
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MEDIA_PATH, path)
            putInt("current_media_index", currentMediaIndex)
        }
        updatePreview(path)
        updateMediaCounter()
        updatePreviewTransform()
    }

    private fun updateMediaCounter() {
        if (mediaPaths.size > 1) {
            tvMediaCounter.text = getString(R.string.label_media_counter, currentMediaIndex + 1, mediaPaths.size)
            tvMediaCounter.visibility = View.VISIBLE
        } else {
            tvMediaCounter.visibility = View.GONE
        }
    }

    private fun deleteMedia() {
        filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }
        saveMediaPath("")
        Toast.makeText(this, R.string.toast_media_removed, Toast.LENGTH_SHORT).show()
    }

    private fun setupTitleSpannable(tvTitle: TextView) {
        val titleText = tvTitle.text.toString()
        val spannable = SpannableStringBuilder(titleText)
        val primaryColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, "#FF6200EE".toColorInt())
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTitle.text = spannable
    }

    private fun checkSelfActive(): Boolean = false
}
