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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
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
    private var detectedCharset: Charset = StandardCharsets.UTF_8
    private var hasBOM: Boolean = false

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
        }

        // Configure editor with improved settings
        setupEditor()
        
        codeEditor.colorScheme =
            if (NightModeHelper.isInNightMode(activity)) SchemeDarcula() else EditorColorScheme()

        updateTitle()
        setupMenu()
        reload()

        codeEditor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            run {
                updateTitle()
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        codeEditor.colorScheme =
            if (NightModeHelper.isInNightMode(activity as AppCompatActivity)) SchemeDarcula() else EditorColorScheme()
        codeEditor.invalidate()
        //TODO: Update toolbar color scheme on dark/light mode toggle
    }

    private fun setupEditor() {
        // Enable IME for better input support
        codeEditor.isEnabled = true
        
        // Configure undo/redo with better stack management
        codeEditor.props.maxUndoStackSize = 100
        
        // Enable line numbers
        codeEditor.isLineNumberEnabled = true
        
        // Configure better input handling
        codeEditor.isWordwrap = false
        
        // Set initial language based on file extension
        detectAndSetLanguage()
    }

    private fun detectAndSetLanguage() {
        val fileName = argsFile.fileName.toString().lowercase()
        // Note: Currently only JavaLanguage is available from dependencies
        // This provides basic syntax highlighting for Java-like languages
        val language = when {
            fileName.endsWith(".java") || fileName.endsWith(".kt") || 
            fileName.endsWith(".js") || fileName.endsWith(".ts") ||
            fileName.endsWith(".c") || fileName.endsWith(".cpp") ||
            fileName.endsWith(".h") || fileName.endsWith(".hpp") -> JavaLanguage()
            else -> null // Plain text for other file types
        }
        codeEditor.setEditorLanguage(language)
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {

                menuInflater.inflate(R.menu.sora_editor, menu)
                menu.findItem(R.id.action_word_warp).isChecked = codeEditor.isWordwrap
                menu.findItem(R.id.action_syntax_highlight).isChecked =
                    codeEditor.editorLanguage is JavaLanguage

                menu.findItem(R.id.action_redo).isEnabled = codeEditor.canRedo()
                menu.findItem(R.id.action_undo).isEnabled = codeEditor.canUndo()

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
                        codeEditor.setEditorLanguage(
                            if (codeEditor.editorLanguage is JavaLanguage) null else JavaLanguage()
                        )
                        item.isChecked = codeEditor.editorLanguage is JavaLanguage
                        true
                    }
                    R.id.action_undo -> {
                        if (codeEditor.canUndo()) {
                            codeEditor.undo()
                            // Update menu state after undo
                            requireActivity().invalidateOptionsMenu()
                        }
                        true
                    }
                    R.id.action_redo -> {
                        if (codeEditor.canRedo()) {
                            codeEditor.redo()
                            // Update menu state after redo
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
        if (binding.progress.isVisible) return
        if (textChanged()) {
            ConfirmReloadDialogFragment.show(this)
        } else reload()
    }

    override fun reload() {

        binding.progress.visibility = View.VISIBLE
        binding.codeEditor.visibility = View.GONE
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            // Check file size before loading
            val fileSize = try {
                Files.size(argsFile)
            } catch (e: IOException) {
                -1L
            }
            
            // Limit file size to 5MB to prevent memory issues
            val maxFileSize = 5 * 1024 * 1024L // 5MB
            if (fileSize > maxFileSize) {
                errorMessage = "File too large (${fileSize / 1024 / 1024}MB). Maximum supported size is ${maxFileSize / 1024 / 1024}MB."
                fileContents = null
            } else {
                fileContents = readFile(argsFile)
            }

            launch(Dispatchers.Main) {
                binding.progress.visibility = View.GONE
                if (fileContents == null && errorMessage != null) {
                    binding.errorText.text = errorMessage
                    binding.errorText.visibility = View.VISIBLE
                    codeEditor.visibility = View.GONE
                } else {
                    codeEditor.visibility = View.VISIBLE
                    codeEditor.setText(fileContents)
                    // Update language detection after file is loaded
                    detectAndSetLanguage()
                }
            }
        }
    }

    private fun save() {
        val text = codeEditor.text.toString()
        
        // Preserve BOM if it was present in the original file
        val bytes = if (hasBOM && detectedCharset == StandardCharsets.UTF_8) {
            // Add UTF-8 BOM using efficient pre-allocation
            val textBytes = text.toByteArray(StandardCharsets.UTF_8)
            ByteArray(3 + textBytes.size).apply {
                this[0] = 0xEF.toByte()
                this[1] = 0xBB.toByte()
                this[2] = 0xBF.toByte()
                System.arraycopy(textBytes, 0, this, 3, textBytes.size)
            }
        } else if (hasBOM && detectedCharset == StandardCharsets.UTF_16LE) {
            // Add UTF-16 LE BOM using efficient pre-allocation
            val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
            ByteArray(2 + textBytes.size).apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xFE.toByte()
                System.arraycopy(textBytes, 0, this, 2, textBytes.size)
            }
        } else if (hasBOM && detectedCharset == StandardCharsets.UTF_16BE) {
            // Add UTF-16 BE BOM using efficient pre-allocation
            val textBytes = text.toByteArray(StandardCharsets.UTF_16BE)
            ByteArray(2 + textBytes.size).apply {
                this[0] = 0xFE.toByte()
                this[1] = 0xFF.toByte()
                System.arraycopy(textBytes, 0, this, 2, textBytes.size)
            }
        } else {
            // Save with detected charset without BOM
            text.toByteArray(detectedCharset)
        }

        FileJobService.write(argsFile, bytes, requireContext()) { success ->
            if (success) {
                fileContents = text
                showToast(getString(R.string.text_editor_save_success))
                updateTitle()   //Fix title do not update after save
            }
        }
    }

    private fun readFile(file: Path): String? {
        return try {
            errorMessage = null
            val bytes = Files.readAllBytes(file)
            
            // Detect encoding and BOM
            val (charset, bomSize) = detectCharsetAndBOM(bytes)
            detectedCharset = charset
            hasBOM = bomSize > 0
            
            // Convert bytes to string, skipping BOM if present
            String(bytes, bomSize, bytes.size - bomSize, charset)
        } catch (err: Throwable) {
            if (err !is OutOfMemoryError && err !is IOException) throw err
            errorMessage = err.localizedMessage
            null
        }
    }

    /**
     * Detect charset and BOM from byte array
     * Returns pair of (Charset, BOM size in bytes)
     */
    private fun detectCharsetAndBOM(bytes: ByteArray): Pair<Charset, Int> {
        if (bytes.isEmpty()) {
            return Pair(StandardCharsets.UTF_8, 0)
        }
        
        // Check for UTF-8 BOM (EF BB BF)
        if (bytes.size >= 3 && 
            bytes[0] == 0xEF.toByte() && 
            bytes[1] == 0xBB.toByte() && 
            bytes[2] == 0xBF.toByte()) {
            return Pair(StandardCharsets.UTF_8, 3)
        }
        
        // Check for UTF-16 LE BOM (FF FE)
        if (bytes.size >= 2 && 
            bytes[0] == 0xFF.toByte() && 
            bytes[1] == 0xFE.toByte()) {
            return Pair(StandardCharsets.UTF_16LE, 2)
        }
        
        // Check for UTF-16 BE BOM (FE FF)
        if (bytes.size >= 2 && 
            bytes[0] == 0xFE.toByte() && 
            bytes[1] == 0xFF.toByte()) {
            return Pair(StandardCharsets.UTF_16BE, 2)
        }
        
        // For files without BOM, default to UTF-8
        // Note: UTF-16 without BOM is ambiguous and requires more sophisticated 
        // detection. We default to UTF-8 as it's the most common encoding for text files.
        return Pair(StandardCharsets.UTF_8, 0)
    }

    private fun textChanged() =
        errorMessage == null && fileContents != null && codeEditor.text.toString() != fileContents


    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

}
