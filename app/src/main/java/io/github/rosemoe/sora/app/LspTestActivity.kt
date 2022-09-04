/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2022  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.lsp.client.connection.SocketStreamConnectionProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspEditorManager
import io.github.rosemoe.sora.lsp.requests.Timeout
import io.github.rosemoe.sora.lsp.requests.Timeouts
import io.github.rosemoe.sora.lsp.utils.URIUtils
import io.github.rosemoe.sora.text.ContentCreator
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.*
import java.net.ServerSocket
import java.util.zip.ZipFile

class LspTestActivity : AppCompatActivity() {
    private lateinit var editor: CodeEditor

    private lateinit var lspEditor: LspEditor


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        editor = CodeEditor(this)

        setContentView(editor)

        val font = Typeface.createFromAsset(assets, "JetBrainsMono-Regular.ttf")

        editor.apply {
            typefaceText = font
            typefaceLineNumber = font
        }

        ensureTextmateTheme()

        lifecycleScope.launch {

            unAssets()

            Toast.makeText(this@LspTestActivity, "Starting Language Server...", Toast.LENGTH_SHORT)
                .show()
            editor.editable = false

            connectToLanguageServer()

            setEditorText()
        }
    }

    private suspend fun setEditorText() {
        val text = withContext(Dispatchers.IO) {
            ContentCreator.fromStream(
                externalCacheDir?.resolve("testProject/sample.xml")?.inputStream()
            )
        }
        editor.setText(text, null)
    }

    private suspend fun unAssets() = withContext(Dispatchers.IO) {

        val zipFile = ZipFile(packageResourcePath)
        val zipEntries = zipFile.entries()
        while (zipEntries.hasMoreElements()) {
            val zipEntry = zipEntries.nextElement()
            val fileName = zipEntry.name
            if (fileName.startsWith("assets/testProject/")) {
                val inputStream = zipFile.getInputStream(zipEntry)
                //The compiler will be optimized here, don't worry
                val filePath = externalCacheDir?.resolve(fileName.substring("assets/".length))
                filePath?.parentFile?.mkdirs()
                val outputStream = FileOutputStream(filePath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
            }
        }
        zipFile.close()
    }


    private suspend fun connectToLanguageServer() = withContext(Dispatchers.IO) {

        val port = randomPort()

        val projectPath = externalCacheDir?.resolve("testProject")?.absolutePath ?: ""

        startService(
            Intent(this@LspTestActivity, LspLanguageServerService::class.java)
                .apply {
                    putExtra("port", port)
                }
        )

        val serverDefinition = CustomLanguageServerDefinition(".xml") {
            SocketStreamConnectionProvider {
                port
            }
        }


        withContext(Dispatchers.Main) {

            lspEditor = LspEditorManager
                .getOrCreateEditorManager(projectPath)
                .createEditor(
                    URIUtils.fileToURI("$projectPath/sample.xml").toString(),
                    serverDefinition
                )

            val wrapperLanguage = createTextMateLanguage()

            lspEditor.setWrapperLanguage(wrapperLanguage)

            lspEditor.editor = editor

        }


        lifecycleScope.launch(Dispatchers.IO) {
            delay(Timeout.getTimeout(Timeouts.INIT).toLong()) //wait for server start
            lspEditor.connect()
            withContext(Dispatchers.Main) {
                editor.editable = true
                Toast.makeText(
                    this@LspTestActivity,
                    "Initialized Language server",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    }

    private fun randomPort(): Int {
        val serverSocket = ServerSocket(0)

        val port = serverSocket.localPort
        serverSocket.close()
        return port
    }

    private fun createTextMateLanguage(): TextMateLanguage {
        return TextMateLanguage.createNoCompletion(
            IGrammarSource.fromInputStream(
                assets.open("textmate/xml/syntaxes/xml.tmLanguage.json"),
                "xml.tmLanguage.json",
                null
            ),
            InputStreamReader(assets.open("textmate/xml/language-configuration.json")),
            (editor.colorScheme as TextMateColorScheme).themeSource
        )
    }

    private fun ensureTextmateTheme() {
        var editorColorScheme = editor.colorScheme
        if (editorColorScheme !is TextMateColorScheme) {
            val themeSource = IThemeSource.fromInputStream(
                assets.open("textmate/QuietLight.tmTheme"),
                "QuietLight.tmTheme",
                null
            )
            editorColorScheme = TextMateColorScheme.create(themeSource)
            editor.colorScheme = editorColorScheme
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_lsp, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.code_format) {
            val cursor = editor.text.cursor
            if (cursor.isSelected) {
                editor.formatCodeAsync(cursor.left(), cursor.right())
            } else {
                editor.formatCodeAsync()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()

        editor.release()
        LspEditorManager.closeAllManager()
        stopService(Intent(this@LspTestActivity, LspLanguageServerService::class.java))
    }
}