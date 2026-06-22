package com.example.trekmesh

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yalantis.ucrop.UCrop
import java.io.File

class ComposeMessageActivity : AppCompatActivity() {

    private var selectedImagePath: String? = null
    private var selectedType: String = "INFO"

    private lateinit var btnTypeInfo: Button
    private lateinit var btnTypeSos: Button
    private lateinit var btnTypeBroadcast: Button
    private lateinit var radioPriority: RadioGroup
    private lateinit var editText: EditText
    private lateinit var editDescription: EditText
    private lateinit var imagePreview: ImageView
    private lateinit var btnPickImage: Button
    private lateinit var btnRemoveImage: Button

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { launchCrop(it) }
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val croppedUri = UCrop.getOutput(result.data!!) ?: return@registerForActivityResult
            val path = croppedUri.path ?: return@registerForActivityResult
            selectedImagePath = path
            imagePreview.setImageBitmap(BitmapFactory.decodeFile(path))
            imagePreview.visibility = View.VISIBLE
            btnRemoveImage.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose_message)

        supportActionBar?.apply {
            title = "Nuovo messaggio"
            setDisplayHomeAsUpEnabled(true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.compose_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        btnTypeInfo      = findViewById(R.id.btn_type_info)
        btnTypeSos       = findViewById(R.id.btn_type_sos)
        btnTypeBroadcast = findViewById(R.id.btn_type_broadcast)
        radioPriority    = findViewById(R.id.radio_priority)
        editText         = findViewById(R.id.edit_text)
        editDescription  = findViewById(R.id.edit_description)
        imagePreview     = findViewById(R.id.image_preview)
        btnPickImage     = findViewById(R.id.btn_pick_image)
        btnRemoveImage   = findViewById(R.id.btn_remove_image)

        if (UserRolePrefs.getRole(this) == UserRole.RIFUGIO) {
            btnTypeBroadcast.visibility = View.VISIBLE
        }

        setTypeSelected("INFO")

        btnTypeInfo.setOnClickListener      { setTypeSelected("INFO") }
        btnTypeSos.setOnClickListener       { setTypeSelected("SOS") }
        btnTypeBroadcast.setOnClickListener { setTypeSelected("BROADCAST") }

        btnPickImage.setOnClickListener   { pickImageLauncher.launch("image/*") }
        btnRemoveImage.setOnClickListener { clearImage() }

        findViewById<Button>(R.id.btn_send).setOnClickListener { trySend() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun launchCrop(sourceUri: Uri) {
        val outDir = File(filesDir, "images").also { it.mkdirs() }
        val destUri = Uri.fromFile(File(outDir, "crop_${System.currentTimeMillis()}.jpg"))
        val intent = UCrop.of(sourceUri, destUri)
            .withOptions(UCrop.Options().apply {
                setFreeStyleCropEnabled(true)
                setCompressionQuality(75)
                setMaxBitmapSize(1024)
                setToolbarColor(0xFF1A1A1A.toInt())
                setStatusBarColor(0xFF121212.toInt())
                setToolbarWidgetColor(0xFFFFFFFF.toInt())
                setActiveControlsWidgetColor(0xFF4FC3F7.toInt())
            })
            .getIntent(this)
        cropLauncher.launch(intent)
    }

    private fun setTypeSelected(type: String) {
        selectedType = type
        btnTypeInfo.alpha      = if (type == "INFO") 1f else 0.4f
        btnTypeSos.alpha       = if (type == "SOS") 1f else 0.4f
        btnTypeBroadcast.alpha = if (type == "BROADCAST") 1f else 0.4f
    }

    private fun trySend() {
        val text = editText.text.toString().trim()
        if (text.isEmpty()) {
            editText.error = "Il testo è obbligatorio"
            return
        }
        val priority = when (radioPriority.checkedRadioButtonId) {
            R.id.radio_priority_2 -> 2
            R.id.radio_priority_3 -> 3
            else -> 1
        }
        TrekMeshBus.sendMessage(
            OutgoingMessage(
                type        = selectedType,
                priority    = priority,
                text        = text,
                description = editDescription.text.toString().trim(),
                imagePath   = selectedImagePath
            )
        )
        finish()
    }

    private fun clearImage() {
        selectedImagePath?.let { File(it).delete() }
        selectedImagePath = null
        imagePreview.setImageDrawable(null)
        imagePreview.visibility = View.GONE
        btnRemoveImage.visibility = View.GONE
    }
}
