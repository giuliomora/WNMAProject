package com.example.trekmesh

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.io.File
import java.io.FileOutputStream

class ComposeMessageDialog : DialogFragment() {

    private var selectedImagePath: String? = null
    private var selectedType: String = "INFO"

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    private lateinit var btnTypeInfo: Button
    private lateinit var btnTypeSos: Button
    private lateinit var radioPriority: RadioGroup
    private lateinit var editText: EditText
    private lateinit var editDescription: EditText
    private lateinit var imagePreview: ImageView
    private lateinit var btnPickImage: Button
    private lateinit var btnRemoveImage: Button

    override fun onAttach(context: Context) {
        super.onAttach(context)
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveAndPreviewImage(it) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_compose_message, null)

        btnTypeInfo      = view.findViewById(R.id.btn_type_info)
        btnTypeSos       = view.findViewById(R.id.btn_type_sos)
        radioPriority    = view.findViewById(R.id.radio_priority)
        editText         = view.findViewById(R.id.edit_text)
        editDescription  = view.findViewById(R.id.edit_description)
        imagePreview     = view.findViewById(R.id.image_preview)
        btnPickImage     = view.findViewById(R.id.btn_pick_image)
        btnRemoveImage   = view.findViewById(R.id.btn_remove_image)

        setTypeSelected("INFO")

        btnTypeInfo.setOnClickListener { setTypeSelected("INFO") }
        btnTypeSos.setOnClickListener  { setTypeSelected("SOS") }

        btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        btnRemoveImage.setOnClickListener { clearImage() }

        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.btn_send).setOnClickListener { trySend() }

        return AlertDialog.Builder(requireContext())
            .setTitle("Nuovo messaggio")
            .setView(view)
            .create()
    }

    private fun setTypeSelected(type: String) {
        selectedType = type
        val infoAlpha = if (type == "INFO") 1f else 0.4f
        val sosAlpha  = if (type == "SOS")  1f else 0.4f
        btnTypeInfo.alpha = infoAlpha
        btnTypeSos.alpha  = sosAlpha
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
        dismiss()
    }

    private fun saveAndPreviewImage(uri: Uri) {
        try {
            val stream = requireContext().contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(stream)
            stream.close()

            val compressed = compressBitmap(original)
            val msgId = "tmp_${System.currentTimeMillis()}"
            val outDir = File(requireContext().filesDir, "images").also { it.mkdirs() }
            val outFile = File(outDir, "$msgId.jpg")
            FileOutputStream(outFile).use { compressed.compress(Bitmap.CompressFormat.JPEG, 75, it) }

            selectedImagePath = outFile.absolutePath
            imagePreview.setImageBitmap(compressed)
            imagePreview.visibility = View.VISIBLE
            btnRemoveImage.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearImage() {
        selectedImagePath?.let { File(it).delete() }
        selectedImagePath = null
        imagePreview.setImageDrawable(null)
        imagePreview.visibility = View.GONE
        btnRemoveImage.visibility = View.GONE
    }

    // Max 1024x1024, JPEG 75%
    private fun compressBitmap(src: Bitmap): Bitmap {
        val maxDim = 1024
        if (src.width <= maxDim && src.height <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }
}
