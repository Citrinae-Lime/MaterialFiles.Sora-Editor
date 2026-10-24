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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SoraEditorFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import java.io.IOException
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

    private var isSettingText = false
    private var isTextChanged = false
    private var isSaving = false
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)

        codeEditor.setEditorLanguage(JavaLanguage())
        codeEditor.colorScheme =
            if (NightModeHelper.isInNightMode(activity)) SchemeDarcula() else EditorColorScheme()

        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                ConfirmCloseDialogFragment.show(this@SoraEditorFragment)
            }
        }
        addOnBackPressedCallback(onBackPressedCallback)

        setupMenu()

        val restoredText = savedInstanceState?.getString(KEY_MODIFIED_TEXT)
        if (restoredText != null) {
            isSettingText = true
            codeEditor.setText(restoredText)
            isSettingText = false
            isTextChanged = true
            onBackPressedCallback.isEnabled = true
            updateTitle()
            binding.codeEditor.isVisible = true
        } else {
            updateTitle()
            reload()
        }

        codeEditor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (isSettingText) return@subscribeEvent
            if (!isTextChanged) {
                isTextChanged = true
                updateTitle()
                onBackPressedCallback.isEnabled = true
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isTextChanged && codeEditor.text.length < MAX_SAVED_STATE_CHARS) {
            outState.putString(KEY_MODIFIED_TEXT, codeEditor.text.toString())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val activity = requireActivity() as AppCompatActivity
        codeEditor.colorScheme =
            if (NightModeHelper.isInNightMode(activity)) SchemeDarcula() else EditorColorScheme()
        codeEditor.invalidate()
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.sora_editor, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.action_word_warp)?.isChecked = codeEditor.isWordwrap
                menu.findItem(R.id.action_syntax_highlight)?.isChecked =
                    codeEditor.editorLanguage is JavaLanguage
                menu.findItem(R.id.action_undo)?.isEnabled = codeEditor.canUndo()
                menu.findItem(R.id.action_redo)?.isEnabled = codeEditor.canRedo()
                menu.findItem(R.id.action_save)?.isEnabled =
                    fileContents != null && errorMessage == null && !isSaving
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.action_save -> {
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
                            if (!codeEditor.canUndo()) {
                                isTextChanged = false
                                updateTitle()
                                onBackPressedCallback.isEnabled = false
                            }
                            requireActivity().invalidateOptionsMenu()
                        }
                        true
                    }
                    R.id.action_redo -> {
                        if (codeEditor.canRedo()) {
                            codeEditor.redo()
                            isTextChanged = true
                            updateTitle()
                            onBackPressedCallback.isEnabled = true
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
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    fun onSupportNavigateUp(): Boolean {
        if (textChanged()) {
            ConfirmCloseDialogFragment.show(this)
            return true
        }
        return false
    }

    fun onFinish(): Boolean = onSupportNavigateUp()

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
        } else {
            reload()
        }
    }

    override fun reload() {
        if (::onBackPressedCallback.isInitialized) {
            onBackPressedCallback.isEnabled = false
        }
        loadJob?.cancel()

        binding.progress.isVisible = true
        binding.codeEditor.isVisible = false
        binding.errorText.isVisible = false

        loadJob = lifecycleScope.launch {
            val contents = withContext(Dispatchers.IO) {
                readFile(argsFile)
            }
            binding.progress.isVisible = false
            if (contents == null && errorMessage != null) {
                binding.errorText.text = errorMessage
                binding.errorText.isVisible = true
                binding.codeEditor.isVisible = false
            } else {
                fileContents = contents
                binding.codeEditor.isVisible = true
                isSettingText = true
                binding.codeEditor.setText(contents)
                isSettingText = false
                isTextChanged = false
                updateTitle()
                if (::onBackPressedCallback.isInitialized) {
                    onBackPressedCallback.isEnabled = false
                }
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    private fun save() {
        if (isSaving || fileContents == null || errorMessage != null) return
        isSaving = true
        requireActivity().invalidateOptionsMenu()

        val text = codeEditor.text.toString()
        val bytes = text.toByteArray(StandardCharsets.UTF_8)

        FileJobService.write(argsFile, bytes, requireContext()) { success ->
            isSaving = false
            if (success) {
                fileContents = text
                isTextChanged = (codeEditor.text.toString() != text)
                showToast(getString(R.string.text_editor_save_success))
                updateTitle()
                if (::onBackPressedCallback.isInitialized) {
                    onBackPressedCallback.isEnabled = isTextChanged
                }
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    private fun readFile(file: Path): String? = runCatching {
        errorMessage = null
        val size = file.size()
        if (size > MAX_FILE_SIZE) {
            throw IOException("File size ($size bytes) is too large")
        }
        String(Files.readAllBytes(file))
    }.getOrElse { err ->
        if (err !is OutOfMemoryError && err !is IOException) throw err
        errorMessage = err.localizedMessage ?: err.toString()
        null
    }

    private fun textChanged(): Boolean = isTextChanged

    companion object {
        private const val MAX_FILE_SIZE = 2 * 1024 * 1024L
        private const val MAX_SAVED_STATE_CHARS = 500_000
        private const val KEY_MODIFIED_TEXT = "SoraEditorFragment.modified_text"
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs
}
