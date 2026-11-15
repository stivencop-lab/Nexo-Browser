package com.nexo.browser

import android.view.Window
import android.view.WindowManager

/** Keep private pages out of system screenshots, recordings and recents thumbnails. */
object WindowPrivacy {
    fun apply(window: Window, private: Boolean) {
        if (private) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun inherit(child: Window?, parent: Window) {
        child ?: return
        apply(child, parent.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
