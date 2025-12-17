package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import com.google.android.material.color.MaterialColors
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SoraEditorFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class SoraEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: SoraEditorFragmentBinding
    private lateinit var codeEditor: CodeEditor

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var fileContents: String? = null
    private var errorMessage: String? = null
    private var isLoading = false
    private var detectedCharset: Charset = StandardCharsets.UTF_8
    private var hadBom: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenStarted {
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    ConfirmCloseDialogFragment.show(this@SoraEditorFragment)
                }
            }
            launch {
                onBackPressedCallback.isEnabled = textChanged()
            }
            addOnBackPressedCallback(onBackPressedCallback)
        }

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            showToast(getString(R.string.activity_not_found))
            return finish()
        }

        this.argsFile = argsFile
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SoraEditorFragmentBinding.inflate(inflater, container, false).also {
        binding = it
        codeEditor = it.codeEditor
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            updateToolbarColors()
        }

        codeEditor.isFocusableInTouchMode = true

        setLanguageForFile(argsFile.fileName.toString())
        applyColorScheme()

        updateTitle()
        setupMenu()
        reload()

        codeEditor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            updateTitle()
            requireActivity().invalidateOptionsMenu()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColorScheme()
        codeEditor.invalidate()
        updateToolbarColors()
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.sora_editor, menu)
                syncMenu(menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                syncMenu(menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.action_save -> {
                        if (fileContents != null && errorMessage == null)
                            save()
                        true
                    }
                    R.id.action_word_warp -> {
                        codeEditor.isWordwrap = !codeEditor.isWordwrap
                        item.isChecked = codeEditor.isWordwrap
                        true
                    }
                    R.id.action_syntax_highlight -> {
                        if (codeEditor.editorLanguage == null) {
                            setLanguageForFile(argsFile.fileName.toString())
                        } else {
                            codeEditor.setEditorLanguage(null)
                            currentLanguage = null
                        }
                        item.isChecked = codeEditor.editorLanguage != null
                        true
                    }
                    R.id.action_undo -> {
                        if (codeEditor.canUndo()) {
                            codeEditor.undo()
                            requireActivity().invalidateOptionsMenu()
                        }
                        true
                    }
                    R.id.action_redo -> {
                        if (codeEditor.canRedo()) {
                            codeEditor.redo()
                            requireActivity().invalidateOptionsMenu()
                        }
                        true
                    }
                    R.id.action_reload -> {
                        onReload()
                        true
                    }
                    else -> false
                }

            private fun syncMenu(menu: Menu) {
                menu.findItem(R.id.action_word_warp).isChecked = codeEditor.isWordwrap
                menu.findItem(R.id.action_syntax_highlight).isChecked =
                    codeEditor.editorLanguage != null
                menu.findItem(R.id.action_redo).isEnabled = codeEditor.canRedo()
                menu.findItem(R.id.action_undo).isEnabled = codeEditor.canUndo()
            }
        })
    }

    fun onSupportNavigateUp(): Boolean {
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        codeEditor.release()
    }

    private fun updateTitle() {
        val fileName = argsFile.fileName
        requireActivity().title = getString(
            if (textChanged()) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (isLoading) return
        if (textChanged()) {
            ConfirmReloadDialogFragment.show(this)
        } else reload()
    }

    override fun reload() {
        if (isLoading) return
        isLoading = true

        binding.progress.visibility = View.VISIBLE
        binding.codeEditor.visibility = View.GONE
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val result = readFileAsText(argsFile)

            launch(Dispatchers.Main) {
                isLoading = false
                binding.progress.visibility = View.GONE
                if (result == null && errorMessage != null) {
                    binding.errorText.text = errorMessage
                    binding.errorText.visibility = View.VISIBLE
                    codeEditor.visibility = View.GONE
                } else {
                    codeEditor.visibility = View.VISIBLE
                    codeEditor.setText(result)
                    fileContents = result
                    updateTitle()
                    // Request focus after content load to reduce IME/focus oddities, but avoid stealing
                    // focus if something else is already focused (e.g. toolbar/search).
                    if (!codeEditor.hasFocus() && requireActivity().currentFocus == null) {
                        codeEditor.requestFocus()
                    }
                }
            }
        }
    }

    private fun save() {
        val text = codeEditor.text.toString()
        val charset = detectedCharset
        FileJobService.write(argsFile, text.toByteArray(charset), requireContext()) { success ->
            if (success) {
                if (hadBom) {
                    ensureBomIfNeeded(argsFile, charset)
                }
                fileContents = text
                showToast(getString(R.string.text_editor_save_success))
                updateTitle()
            }
        }
    }

    private fun readFileAsText(file: Path): String? {
        return try {
            errorMessage = null
            detectedCharset = detectEncoding(file)
            Files.newBufferedReader(file, detectedCharset).use { it.readText() }
        } catch (err: Throwable) {
            if (err !is OutOfMemoryError && err !is IOException) throw err
            errorMessage = err.localizedMessage
            null
        }
    }

    private fun textChanged() =
        errorMessage == null && fileContents != null && codeEditor.text.toString() != fileContents

    private fun applyColorScheme() {
        codeEditor.colorScheme =
            if (NightModeHelper.isInNightMode(activity as AppCompatActivity)) SchemeDarcula()
            else EditorColorScheme()
    }

    private fun updateToolbarColors() {
        val toolbar = binding.toolbar
        val surface = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorSurface, 0)
        val onSurface = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface, 0)
        toolbar.setBackgroundColor(surface)
        toolbar.setTitleTextColor(onSurface)
        toolbar.setSubtitleTextColor(onSurface)
        toolbar.navigationIcon?.let { icon ->
            val wrapped = DrawableCompat.wrap(icon).mutate()
            DrawableCompat.setTint(wrapped, onSurface)
            toolbar.navigationIcon = wrapped
        }
        toolbar.overflowIcon?.let { icon ->
            val wrapped = DrawableCompat.wrap(icon).mutate()
            DrawableCompat.setTint(wrapped, onSurface)
            toolbar.overflowIcon = wrapped
        }
    }

    private fun setLanguageForFile(name: String) {
        // Only keep Java for now. Other types should fall back to plain text until TextMate are integrated.
        // TODO: TextMate
        codeEditor.setEditorLanguage(
            if (name.endsWith(".java")) JavaLanguage() else null
        )
        applyColorScheme()
    }

    private fun detectEncoding(file: Path): Charset {
        return try {
            BufferedInputStream(Files.newInputStream(file)).use { input ->
                val bom = ByteArray(3)
                val read = input.read(bom)
                when {
                    read >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte() -> {
                        hadBom = true; StandardCharsets.UTF_8
                    }
                    read >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte() -> {
                        hadBom = true; StandardCharsets.UTF_16BE
                    }
                    read >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte() -> {
                        hadBom = true; StandardCharsets.UTF_16LE
                    }
                    else -> {
                        // If we can't detect encoding by BOM, default to UTF-8.
                        // NOTE: This may mis-detect legacy encodings without BOM.
                        hadBom = false; StandardCharsets.UTF_8
                    }
                }
            }
        } catch (_: Throwable) {
            // If encoding detection fails for any reason, fall back to UTF-8.
            hadBom = false
            StandardCharsets.UTF_8
        }
    }

    private fun ensureBomIfNeeded(file: Path, charset: Charset) {
        val bom = when (charset) {
            StandardCharsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            StandardCharsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            StandardCharsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> return
        }
        // Check if BOM is already present to avoid repeatedly prepending it on each save.
        if (hasBom(file, bom)) return
        val original = Files.readAllBytes(file)
        BufferedOutputStream(Files.newOutputStream(file)).use {
            it.write(bom)
            it.write(original)
        }
    }

    private fun hasBom(file: Path, bom: ByteArray): Boolean {
        return try {
            BufferedInputStream(Files.newInputStream(file)).use { input ->
                val head = ByteArray(bom.size)
                val read = input.read(head)
                read == bom.size && head.contentEquals(bom)
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs
}