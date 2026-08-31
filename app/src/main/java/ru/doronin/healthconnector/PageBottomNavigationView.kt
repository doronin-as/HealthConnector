package ru.doronin.healthconnector

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView

class PageBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle
) : BottomNavigationView(context, attrs, defStyleAttr) {

    init {
        setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (selectedItemId == 0) selectedItemId = R.id.nav_sync
        showPage(selectedItemId)
    }

    private fun showPage(itemId: Int) {
        val root = rootView ?: return
        val syncPage = root.findViewById<View>(R.id.syncPage) ?: return
        val settingsPage = root.findViewById<View>(R.id.settingsPage) ?: return

        val settingsSelected = itemId == R.id.nav_settings
        syncPage.visibility = if (settingsSelected) View.GONE else View.VISIBLE
        settingsPage.visibility = if (settingsSelected) View.VISIBLE else View.GONE
    }
}
