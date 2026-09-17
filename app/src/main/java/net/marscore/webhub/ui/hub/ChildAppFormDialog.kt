package net.marscore.webhub.ui.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.icons.FaviconFetcher
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.icons.TitleFetcher
import net.marscore.webhub.icons.UrlValidator
import java.io.File

/** Add wizard and locked-URL editor for a child app. */
class ChildAppFormDialog private constructor(
    private val host: AppCompatActivity,
    private val existing: ChildApp?,
    private val pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>,
    private val pickImageBridge: PickImageBridge,
    private val onSave: (FormResult) -> Unit
) {

    data class FormResult(
        val name: String,
        val url: String,
        val iconSource: String,
        val uploadUri: Uri?,
        val presetKey: String?,
        val faviconBitmap: Bitmap?,
        val uaMode: String,
        val zoomPercent: Int,
        val ignoreSsl: Boolean
    )

    class PickImageBridge {
        var consumer: ((Uri) -> Unit)? = null
    }

    private lateinit var view: View
    private lateinit var dialog: AlertDialog
    private lateinit var step1Root: View
    private lateinit var step2Root: View
    private lateinit var fetchingRoot: View
    private lateinit var step1UrlInput: TextInputEditText
    private lateinit var step1UrlLayout: TextInputLayout
    private lateinit var step1IgnoreSsl: CheckBox
    private lateinit var editUrlLayout: TextInputLayout
    private lateinit var editUrlInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText
    private lateinit var nameLayout: TextInputLayout
    private lateinit var iconPreview: ImageView
    private lateinit var fetchError: TextView
    private lateinit var retryButton: TextView
    private lateinit var iconSourceGroup: RadioGroup
    private lateinit var uploadPanel: View
    private lateinit var uploadPreview: ImageView
    private lateinit var presetGrid: RecyclerView
    private lateinit var presetAdapter: PresetIconAdapter
    private lateinit var zoomSlider: Slider
    private lateinit var zoomValue: TextView
    private lateinit var editIgnoreSsl: CheckBox

    private var pickedImageUri: Uri? = null
    private var fetchedBitmap: Bitmap? = null
    private var lastFetchUrl: String? = null
    private var lastFetchIgnoreSsl: Boolean? = null
    private var fetchJob: Job? = null
    private var fetchReturnsToStep1 = true

    private fun isEdit(): Boolean = existing != null

    fun show() {
        view = LayoutInflater.from(host).inflate(R.layout.dialog_child_app, null)
        bindViews()
        prefill()

        pickImageBridge.consumer = ::onImagePicked
        dialog = MaterialAlertDialogBuilder(host)
            .setTitle(if (isEdit()) R.string.wizard_edit_title else R.string.wizard_add_title)
            .setView(view)
            .setPositiveButton(if (isEdit()) R.string.wizard_save else R.string.wizard_next, null)
            .setNegativeButton(if (isEdit()) R.string.wizard_cancel else R.string.wizard_cancel, null)
            .create()
        dialog.setOnDismissListener {
            fetchJob?.cancel()
            pickImageBridge.consumer = null
        }
        dialog.show()
        configureButtons()
        if (isEdit()) showStep2() else showStep1()
    }

    private fun bindViews() {
        step1Root = view.findViewById(R.id.step1_root)
        step2Root = view.findViewById(R.id.step2_root)
        fetchingRoot = view.findViewById(R.id.fetching_root)
        step1UrlInput = view.findViewById(R.id.step1_url_input)
        step1UrlLayout = view.findViewById(R.id.step1_url_layout)
        step1IgnoreSsl = view.findViewById(R.id.step1_ignore_ssl)
        editUrlLayout = view.findViewById(R.id.edit_url_layout)
        editUrlInput = view.findViewById(R.id.edit_url_input)
        nameInput = view.findViewById(R.id.name_input)
        nameLayout = view.findViewById(R.id.name_layout)
        iconPreview = view.findViewById(R.id.icon_preview)
        fetchError = view.findViewById(R.id.fetch_error)
        retryButton = view.findViewById(R.id.btn_retry_fetch)
        iconSourceGroup = view.findViewById(R.id.icon_source_group)
        uploadPanel = view.findViewById(R.id.upload_panel)
        uploadPreview = view.findViewById(R.id.upload_preview)
        presetGrid = view.findViewById(R.id.preset_grid)
        zoomSlider = view.findViewById(R.id.zoom_slider)
        zoomValue = view.findViewById(R.id.zoom_value)
        editIgnoreSsl = view.findViewById(R.id.edit_ignore_ssl)

        presetAdapter = PresetIconAdapter { preset -> iconPreview.setImageResource(preset.resId) }
        presetGrid.layoutManager = GridLayoutManager(host, 4)
        presetGrid.adapter = presetAdapter
        zoomSlider.stepSize = 5f
        zoomSlider.addOnChangeListener { _, value, _ ->
            zoomValue.text = "${value.toInt()}%"
        }

        iconSourceGroup.setOnCheckedChangeListener { _, checkedId ->
            uploadPanel.visibility = if (checkedId == R.id.icon_source_upload) View.VISIBLE else View.GONE
            presetGrid.visibility = if (checkedId == R.id.icon_source_preset) View.VISIBLE else View.GONE
            if (checkedId == R.id.icon_source_favicon && fetchedBitmap != null) {
                setRoundedBitmap(iconPreview, fetchedBitmap)
            }
        }
        view.findViewById<View>(R.id.btn_pick_image).setOnClickListener {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        retryButton.setOnClickListener {
            val url = existing?.url ?: lastFetchUrl ?: return@setOnClickListener
            val ignoreSsl = if (isEdit()) editIgnoreSsl.isChecked else lastFetchIgnoreSsl == true
            beginFetch(url, ignoreSsl, fromStep1 = false)
        }
        view.findViewById<View>(R.id.btn_cancel_fetch).setOnClickListener {
            fetchJob?.cancel()
            if (fetchReturnsToStep1) {
                lastFetchUrl = null
                lastFetchIgnoreSsl = null
            }
            if (fetchReturnsToStep1) showStep1() else showStep2()
        }
    }

    private fun prefill() {
        val child = existing ?: return
        editUrlInput.setText(child.url)
        editUrlLayout.visibility = View.VISIBLE
        nameInput.setText(child.name)
        editIgnoreSsl.isChecked = child.ignoreSsl
        when (child.uaMode) {
            "mobile" -> view.findViewById<RadioButton>(R.id.ua_mobile).isChecked = true
            "tablet" -> view.findViewById<RadioButton>(R.id.ua_tablet).isChecked = true
            "desktop" -> view.findViewById<RadioButton>(R.id.ua_desktop).isChecked = true
            else -> view.findViewById<RadioButton>(R.id.ua_default).isChecked = true
        }
        zoomSlider.value = child.zoomPercent.takeIf { it in 50..200 }?.toFloat() ?: 100f
        when (child.iconSource) {
            "upload" -> {
                view.findViewById<RadioButton>(R.id.icon_source_upload).isChecked = true
                child.iconPath?.let { path ->
                    if (File(path).exists()) {
                        val uri = Uri.fromFile(File(path))
                        uploadPreview.setImageURI(uri)
                        setRoundedUri(iconPreview, uri)
                    }
                    uploadPreview.visibility = View.VISIBLE
                }
            }
            "preset" -> {
                view.findViewById<RadioButton>(R.id.icon_source_preset).isChecked = true
                presetAdapter.setSelectedKey(child.iconPath)
                child.iconPath?.let { key ->
                    val resId = PresetIcons.resForKey(key)
                    if (resId != 0) iconPreview.setImageResource(resId)
                }
            }
            else -> {
                view.findViewById<RadioButton>(R.id.icon_source_favicon).isChecked = true
                child.iconPath?.let { path ->
                    if (File(path).exists()) setRoundedUri(iconPreview, Uri.fromFile(File(path)))
                }
            }
        }
    }

    private fun onImagePicked(uri: Uri) {
        CropImageDialog.show(host, uri, ::acceptCroppedImage)
    }

    private fun acceptCroppedImage(uri: Uri) {
        pickedImageUri = uri
        setRoundedUri(uploadPreview, uri)
        setRoundedUri(iconPreview, uri)
        uploadPreview.visibility = View.VISIBLE
        view.findViewById<RadioButton>(R.id.icon_source_upload).isChecked = true
    }

    private fun configureButtons() {
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        positive.setOnClickListener {
            // Add mode: positive is 下一步 on step1 but 保存 on step2 — dispatch by visible step,
            // not just by mode (was the "永远无法保存" bug).
            if (isEdit() || step2Root.visibility == View.VISIBLE) attemptSave() else attemptNext()
        }
        negative.setOnClickListener {
            if (isEdit()) {
                dialog.dismiss()
            } else if (step2Root.visibility == View.VISIBLE) {
                showStep1()
            } else {
                dialog.dismiss()
            }
        }
    }

    private fun showStep1() {
        step1Root.visibility = View.VISIBLE
        step2Root.visibility = View.GONE
        fetchingRoot.visibility = View.GONE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = View.VISIBLE
            text = host.getString(R.string.wizard_next)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            visibility = View.VISIBLE
            text = host.getString(R.string.wizard_cancel)
        }
    }

    private fun showStep2() {
        step1Root.visibility = View.GONE
        step2Root.visibility = View.VISIBLE
        fetchingRoot.visibility = View.GONE
        editUrlLayout.visibility = if (isEdit()) View.VISIBLE else View.GONE
        editIgnoreSsl.visibility = if (isEdit()) View.VISIBLE else View.GONE
        if (isEdit()) {
            retryButton.visibility = View.VISIBLE
            retryButton.text = host.getString(R.string.wizard_refetch)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = View.VISIBLE
            text = host.getString(R.string.wizard_save)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            visibility = View.VISIBLE
            text = host.getString(if (isEdit()) R.string.wizard_cancel else R.string.wizard_previous)
        }
    }

    private fun showFetching() {
        step1Root.visibility = View.GONE
        step2Root.visibility = View.GONE
        fetchingRoot.visibility = View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = View.GONE
    }

    private fun attemptNext() {
        step1UrlLayout.error = null
        val normalized = UrlValidator.normalize(step1UrlInput.text?.toString()?.trim().orEmpty())
        if (normalized == null) {
            step1UrlLayout.error = host.getString(R.string.wizard_url_invalid)
            return
        }
        val ignoreSsl = step1IgnoreSsl.isChecked
        if (normalized == lastFetchUrl && ignoreSsl == lastFetchIgnoreSsl) {
            showStep2()
        } else {
            beginFetch(normalized, ignoreSsl, fromStep1 = true)
        }
    }

    private fun beginFetch(url: String, ignoreSsl: Boolean, fromStep1: Boolean) {
        fetchJob?.cancel()
        lastFetchUrl = url
        lastFetchIgnoreSsl = ignoreSsl
        fetchReturnsToStep1 = fromStep1
        showFetching()
        fetchJob = host.lifecycleScope.launch {
            try {
                val favicon = async { FaviconFetcher().fetch(url, ignoreSsl) }
                val title = async { TitleFetcher.fetch(url, ignoreSsl) }
                val faviconResult = favicon.await()
                val titleResult = title.await()
                if (isEdit()) {
                    if (faviconResult.bitmap != null) {
                        fetchedBitmap = faviconResult.bitmap
                        clearFetchError()
                        setRoundedBitmap(iconPreview, fetchedBitmap)
                        view.findViewById<RadioButton>(R.id.icon_source_favicon).isChecked = true
                        showStep2()
                    } else {
                        showEditFetchFailure(faviconResult.error)
                    }
                } else {
                    // Icon and title are independent outcomes: a missing title (login-walled
                    // sites, no <title>) must NOT discard a successfully fetched icon.
                    fetchedBitmap = faviconResult.bitmap
                    if (fetchedBitmap != null) {
                        clearFetchError()
                        nameInput.setText(titleResult ?: DomainDisplay.of(url))
                        setRoundedBitmap(iconPreview, fetchedBitmap)
                        view.findViewById<RadioButton>(R.id.icon_source_favicon).isChecked = true
                    } else {
                        val reason = faviconResult.error ?: "network"
                        showAddFetchFailure(reason, titleResult)
                    }
                    showStep2()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // The inline cancel action restores the previous step.
            }
        }
    }

    private fun showAddFetchFailure(reason: String, titleResult: String?) {
        fetchedBitmap = null
        fetchError.text = errorText(reason)
        fetchError.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        // A successfully fetched title must NOT be discarded just because the icon fetch failed
        // (e.g. login-walled sites whose <title> is public). Prefer the title; fall back to the
        // domain only when no title was retrieved.
        nameInput.setText(titleResult ?: DomainDisplay.of(lastFetchUrl.orEmpty()))
        val fallback = PresetIcons.pickForId(0)
        presetAdapter.setSelectedKey(fallback.key)
        view.findViewById<RadioButton>(R.id.icon_source_preset).isChecked = true
        iconPreview.setImageResource(fallback.resId)
    }

    private fun showEditFetchFailure(reason: String?) {
        showStep2()
        android.widget.Toast.makeText(host, errorText(reason ?: "network"), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clearFetchError() {
        fetchError.visibility = View.GONE
        retryButton.visibility = if (isEdit()) View.VISIBLE else View.GONE
    }

    private fun errorText(reason: String): String = when (reason) {
        "bad_url" -> host.getString(R.string.wizard_error_bad_url)
        "decode" -> host.getString(R.string.wizard_error_decode)
        "network" -> host.getString(R.string.wizard_error_network)
        else -> host.getString(R.string.wizard_error_unknown)
    }

    private fun attemptSave() {
        nameLayout.error = null
        val name = nameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            nameLayout.error = host.getString(R.string.wizard_name_required)
            return
        }
        val url = existing?.url ?: lastFetchUrl ?: step1UrlInput.text?.toString()?.trim().orEmpty()
        val normalized = UrlValidator.normalize(url)
        if (normalized == null) {
            if (isEdit()) editUrlLayout.error = host.getString(R.string.wizard_url_invalid)
            else step1UrlLayout.error = host.getString(R.string.wizard_url_invalid)
            return
        }
        val zoom = zoomSlider.value.toInt()
        val selectedSource = when (iconSourceGroup.checkedRadioButtonId) {
            R.id.icon_source_upload -> "upload"
            R.id.icon_source_preset -> "preset"
            else -> "favicon"
        }
        val uploadUri = if (selectedSource == "upload") pickedImageUri else null
        val source = if (!isEdit() && selectedSource == "favicon" && fetchedBitmap == null) "preset"
        else if (!isEdit() && selectedSource == "upload" && uploadUri == null) "preset"
        else selectedSource
        val presetKey = if (source == "preset") {
            presetAdapter.selectedKey ?: PresetIcons.pickForId(0).key
        } else null
        val uaMode = when (view.findViewById<RadioGroup>(R.id.ua_mode_group).checkedRadioButtonId) {
            R.id.ua_mobile -> "mobile"
            R.id.ua_tablet -> "tablet"
            R.id.ua_desktop -> "desktop"
            else -> "default"
        }
        val result = FormResult(
            name = name,
            url = normalized,
            iconSource = source,
            uploadUri = uploadUri,
            presetKey = presetKey,
            faviconBitmap = if (source == "favicon") fetchedBitmap else null,
            uaMode = uaMode,
            zoomPercent = zoom,
            ignoreSsl = if (isEdit()) editIgnoreSsl.isChecked else step1IgnoreSsl.isChecked
        )
        dialog.dismiss()
        onSave(result)
    }

    private fun setRoundedBitmap(target: ImageView, bitmap: Bitmap?) {
        if (bitmap == null) return
        target.setImageDrawable(RoundedBitmapDrawableFactory.create(host.resources, bitmap).apply {
            cornerRadius = 14f * host.resources.displayMetrics.density
        })
    }

    private fun setRoundedUri(target: ImageView, uri: Uri) {
        val bitmap = BitmapFactory.decodeFile(uri.path)
        if (bitmap != null) setRoundedBitmap(target, bitmap) else target.setImageURI(uri)
    }

    companion object {
        fun show(
            host: AppCompatActivity,
            existing: ChildApp?,
            pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>,
            pickImageBridge: PickImageBridge,
            onSave: (FormResult) -> Unit
        ) {
            ChildAppFormDialog(host, existing, pickImageLauncher, pickImageBridge, onSave).show()
        }
    }
}
