package com.nexo.browser.workspace

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.BrowserTab
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import com.nexo.browser.UrlSafety
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Espacios de trabajo de Nexo Workspace: guarda y restaura colecciones con nombre de
 * pestañas y grupos normales de forma perezosa (solo despierta la pestaña activa al restaurar,
 * sin abrir renderizadores Chromium de golpe para las demás).
 *
 * Privacidad estricta:
 * - Las pestañas privadas (`isPrivate`) y las identidades Burner (`burnerId != null`)
 *   se excluyen SIEMPRE al capturar un espacio de trabajo.
 * - Puro sobre [File]: verificable en pruebas JVM.
 */
object WorkspaceStore {
    const val FILE_NAME = "nexo_workspaces.json"
    const val MAX_WORKSPACES = 30
    const val MAX_TABS_PER_WORKSPACE = 100

    data class WorkspaceTab(
        val url: String,
        val title: String,
        val groupName: String? = null,
        val desktopMode: Boolean = false,
        val pinned: Boolean = false
    )

    data class Workspace(
        val id: String,
        val name: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val activeIndex: Int,
        val tabs: List<WorkspaceTab>
    ) {
        fun groupSummary(): String {
            val groups = tabs.mapNotNull { it.groupName?.takeIf { g -> g.isNotBlank() } }.distinct()
            return if (groups.isEmpty()) "${tabs.size} pestañas"
            else "${tabs.size} pestañas · ${groups.size} grupos (${groups.take(3).joinToString(", ")})"
        }
    }

    private val lock = Any()

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Captura un espacio de trabajo a partir de las pestañas abiertas, excluyendo de forma
     * estricta todas las pestañas privadas, Burner o direcciones no web.
     */
    fun captureFromTabs(
        name: String,
        tabs: List<BrowserTab>,
        activeTabId: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Workspace? {
        val cleanName = sanitizeName(name)
        if (cleanName.isEmpty()) return null
        val eligible = tabs.filter { tab ->
            !tab.isPrivate &&
                tab.burnerId == null &&
                UrlSafety.isWebUrl(tab.url) &&
                !BrowserPrefs.isNexoHome(tab.url)
        }.take(MAX_TABS_PER_WORKSPACE)
        if (eligible.isEmpty()) return null

        val activeIdx = eligible.indexOfFirst { it.id == activeTabId }.takeIf { it >= 0 } ?: 0
        val savedTabs = eligible.map { tab ->
            WorkspaceTab(
                url = UrlSafety.stripCredentials(tab.url),
                title = tab.title.trim().take(200).ifBlank { tab.url },
                groupName = tab.groupName?.trim()?.take(60)?.takeIf { it.isNotBlank() },
                desktopMode = tab.desktopMode,
                pinned = tab.pinned
            )
        }
        return Workspace(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            activeIndex = activeIdx,
            tabs = savedTabs
        )
    }

    fun list(context: Context): List<Workspace> = list(file(context))

    fun list(file: File): List<Workspace> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        val arr = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Workspace>(arr.length())
        for (i in 0 until minOf(arr.length(), MAX_WORKSPACES)) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
            val name = sanitizeName(o.optString("name"))
            if (name.isEmpty()) continue
            val tabsArr = o.optJSONArray("tabs") ?: continue
            val tabs = ArrayList<WorkspaceTab>()
            for (j in 0 until minOf(tabsArr.length(), MAX_TABS_PER_WORKSPACE)) {
                val t = tabsArr.optJSONObject(j) ?: continue
                val url = t.optString("url").trim()
                if (!UrlSafety.isWebUrl(url)) continue
                tabs.add(
                    WorkspaceTab(
                        url = url,
                        title = t.optString("title").trim().take(200).ifBlank { url },
                        groupName = t.optString("groupName").trim().take(60).takeIf { it.isNotBlank() && it != "null" },
                        desktopMode = t.optBoolean("desktopMode", false),
                        pinned = t.optBoolean("pinned", false)
                    )
                )
            }
            if (tabs.isEmpty()) continue
            val activeIndex = o.optInt("activeIndex", 0).coerceIn(0, tabs.lastIndex)
            out.add(
                Workspace(
                    id = id,
                    name = name,
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    updatedAtMs = o.optLong("updatedAtMs", 0L),
                    activeIndex = activeIndex,
                    tabs = tabs
                )
            )
        }
        out.sortedByDescending { it.updatedAtMs }
    }

    fun save(file: File, workspace: Workspace): Boolean = synchronized(lock) {
        if (workspace.tabs.isEmpty()) return false
        val current = list(file).toMutableList()
        val existing = current.indexOfFirst { it.id == workspace.id || it.name.equals(workspace.name, ignoreCase = true) }
        if (existing >= 0) {
            current[existing] = workspace.copy(id = current[existing].id, createdAtMs = current[existing].createdAtMs)
        } else {
            current.add(0, workspace)
        }
        persist(file, current.take(MAX_WORKSPACES))
        true
    }

    fun save(context: Context, workspace: Workspace): Boolean = save(file(context), workspace)

    fun rename(file: File, id: String, newName: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val clean = sanitizeName(newName)
        if (clean.isEmpty()) return false
        val current = list(file).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return false
        current[idx] = current[idx].copy(name = clean, updatedAtMs = nowMs)
        persist(file, current)
        true
    }

    fun delete(file: File, id: String): Boolean = synchronized(lock) {
        val current = list(file).toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) persist(file, current)
        removed
    }

    /**
     * Restaura [workspace] en [TabManager] de forma perezosa: importa las pestañas sin
     * crear sesiones Chromium y activa únicamente la pestaña guardada en [Workspace.activeIndex].
     */
    fun restoreLazily(workspace: Workspace): Int {
        val imported = workspace.tabs.mapNotNull { t ->
            TabManager.importTab(
                url = t.url,
                title = t.title,
                groupName = t.groupName,
                desktopMode = t.desktopMode,
                pinned = t.pinned
            )
        }
        if (imported.isEmpty()) return 0
        val active = imported[workspace.activeIndex.coerceIn(0, imported.lastIndex)]
        TabManager.switchTo(active.id)
        return imported.size
    }

    /** Hoja nativa Flow 3.0 para crear, restaurar, renombrar o eliminar espacios de trabajo. */
    fun showSheet(activity: Activity, onChanged: () -> Unit = {}) {
        lateinit var sheet: Dialog
        val dirFile = file(activity)

        fun openCreateDialog() {
            val input = EditText(activity).apply {
                hint = "Ej. Investigación, Trabajo, Lectura"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                ThemeManager.styleEdit(activity, this)
                val pad = NexoUi.dp(activity, 14f)
                setPadding(pad, pad, pad, pad)
            }
            AlertDialog.Builder(activity)
                .setTitle("Guardar espacio de trabajo")
                .setMessage("Guarda las pestañas normales y sus grupos actuales. Las pestañas privadas y Burner nunca se incluyen.")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar") { _, _ ->
                    val name = input.text?.toString().orEmpty().trim()
                    val captured = captureFromTabs(name, TabManager.allTabs(), TabManager.activeTab()?.id)
                    if (captured == null) {
                        Toastish.show(activity, "Introduce un nombre válido y abre al menos una pestaña web normal.")
                        return@setPositiveButton
                    }
                    save(dirFile, captured)
                    Toastish.show(activity, "Espacio «${captured.name}» guardado (${captured.tabs.size} pestañas).")
                    sheet.dismiss()
                    onChanged()
                    showSheet(activity, onChanged)
                }.show()
        }

        val workspaces = list(dirFile)
        val body = NexoUi.column(activity)
        body.addView(NexoUi.button(activity, "Guardar pestañas actuales como espacio", primary = true) {
            openCreateDialog()
        }, NexoUi.buttonParams(activity, 4f))

        body.addView(NexoUi.note(
            activity,
            "Al abrir un espacio, Nexo importa sus pestañas y grupos de forma perezosa: solo carga la pestaña activa para ahorrar memoria. Las pestañas privadas y Burner nunca se guardan."
        ))

        if (workspaces.isEmpty()) {
            body.addView(NexoUi.emptyState(
                activity,
                R.drawable.ic_menu_workspace,
                "Sin espacios guardados",
                "Guarda conjuntos de pestañas y grupos (por ejemplo «Trabajo», «Estudio» o «Viaje») para cambiar de contexto cuando quieras."
            ))
        } else {
            val fmt = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
            workspaces.forEach { ws ->
                val sub = "${ws.groupSummary()} · ${fmt.format(Date(ws.updatedAtMs))}"
                val row = NexoUi.actionRow(activity, R.drawable.ic_menu_workspace, ws.name, sub) {
                    val count = restoreLazily(ws)
                    sheet.dismiss()
                    Toastish.show(activity, "Espacio «${ws.name}» abierto ($count pestañas en carga perezosa).")
                    onChanged()
                }
                row.setOnLongClickListener {
                    showWorkspaceOptions(activity, dirFile, ws) {
                        sheet.dismiss()
                        onChanged()
                        showSheet(activity, onChanged)
                    }
                    true
                }
                body.addView(row, NexoUi.buttonParams(activity, 6f))
            }
        }

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Espacios de trabajo", "Toca para restaurar · mantén pulsado para editar"),
                body
            )
        )
    }

    private fun showWorkspaceOptions(activity: Activity, file: File, ws: Workspace, onUpdated: () -> Unit) {
        val options = arrayOf("Restaurar espacio", "Renombrar", "Eliminar espacio")
        AlertDialog.Builder(activity)
            .setTitle(ws.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val count = restoreLazily(ws)
                        Toastish.show(activity, "Espacio «${ws.name}» abierto ($count pestañas).")
                        onUpdated()
                    }
                    1 -> {
                        val input = EditText(activity).apply {
                            setText(ws.name)
                            setSelection(ws.name.length)
                            setSingleLine(true)
                            ThemeManager.styleEdit(activity, this)
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Renombrar espacio")
                            .setView(input)
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Guardar") { _, _ ->
                                if (rename(file, ws.id, input.text?.toString().orEmpty())) {
                                    Toastish.show(activity, "Espacio renombrado.")
                                    onUpdated()
                                }
                            }.show()
                    }
                    2 -> {
                        AlertDialog.Builder(activity)
                            .setTitle("¿Eliminar «${ws.name}»?")
                            .setMessage("Se borrará este espacio guardado. Tus pestañas abiertas actualmente no se cierran.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Eliminar") { _, _ ->
                                delete(file, ws.id)
                                Toastish.show(activity, "Espacio eliminado.")
                                onUpdated()
                            }.show()
                    }
                }
            }.show()
    }

    private fun persist(file: File, list: List<Workspace>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        list.forEach { ws ->
            val tabsArr = JSONArray()
            ws.tabs.forEach { t ->
                tabsArr.put(JSONObject()
                    .put("url", t.url)
                    .put("title", t.title)
                    .put("groupName", t.groupName ?: JSONObject.NULL)
                    .put("desktopMode", t.desktopMode)
                    .put("pinned", t.pinned))
            }
            arr.put(JSONObject()
                .put("id", ws.id)
                .put("name", ws.name)
                .put("createdAtMs", ws.createdAtMs)
                .put("updatedAtMs", ws.updatedAtMs)
                .put("activeIndex", ws.activeIndex)
                .put("tabs", tabsArr))
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun sanitizeName(raw: String?): String =
        raw.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(64)
}
