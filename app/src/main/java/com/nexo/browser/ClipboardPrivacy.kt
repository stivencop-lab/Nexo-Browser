package com.nexo.browser

import android.content.ClipData
import android.os.PersistableBundle

/** Android 13+ oculta la vista previa; la misma clave es compatible con Android anteriores. */
object ClipboardPrivacy {
    private const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun plainText(label: String, text: String, sensitive: Boolean): ClipData =
        ClipData.newPlainText(label, text).apply {
            if (sensitive) description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE, true) }
        }
}
