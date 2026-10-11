package de.regepower.dualfiles

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Help dialog: foldable chapters, then version, build day, donation line (in-text links) and FOSS note. */
object Help {
    @Suppress("DEPRECATION")
    fun show(a: Activity) {
        val dp = a.resources.displayMetrics.density
        val titles = a.resources.getStringArray(R.array.help_titles)
        val bodies = a.resources.getStringArray(R.array.help_bodies)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())

        for (i in titles.indices) {
            val head = TextView(a)
            head.textSize = 16f
            head.typeface = android.graphics.Typeface.DEFAULT_BOLD
            head.setTextColor(a.getColor(R.color.md_on_container))
            head.setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            val body = TextView(a)
            body.text = bodies[i]
            body.textSize = 14f
            body.setTextColor(a.getColor(R.color.md_on_surface))
            body.setPadding(0, 0, 0, (8 * dp).toInt())
            body.visibility = if (i == 0) View.VISIBLE else View.GONE
            // Only the first chapter starts open.
            fun sync() {
                val open = body.visibility == View.VISIBLE
                head.text = a.getString(if (open) R.string.help_open else R.string.help_closed, titles[i])
                head.stateDescription = a.getString(if (open) R.string.help_expanded else R.string.help_collapsed)
            }
            head.setOnClickListener {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                sync()
            }
            sync()
            col.addView(head)
            col.addView(body)
        }

        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        val meta = a.packageManager.getApplicationInfo(a.packageName, PackageManager.GET_META_DATA).metaData
        val day = meta?.getString("build_date")?.let {
            LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        } ?: ""
        val footer = TextView(a)
        footer.textSize = 12f
        footer.setTextColor(a.getColor(R.color.md_on_surface_variant))
        footer.setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        // The donation links live in the string (<a href>, kept by getText); the source link gets its own span.
        // No autoLink/Linkify: they would drop the existing links.
        val url = a.getString(R.string.source_url)
        val foss = a.getString(R.string.foss_text, url)
        val text = SpannableStringBuilder()
            .append(a.getString(R.string.app_name)).append(' ').append(info.versionName).append(" · ").append(day).append('\n')
            .append(a.getText(R.string.donate_text)).append("\n\n")
        val start = text.length + foss.indexOf(url)
        text.append(foss)
        text.setSpan(URLSpan(url), start, start + url.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        footer.text = text
        footer.movementMethod = LinkMovementMethod.getInstance()
        col.addView(footer)

        val scroll = ScrollView(a)
        scroll.addView(col)
        val d = AlertDialog.Builder(a)
            .setTitle(R.string.help)
            .setView(scroll)
            .setPositiveButton(R.string.help_ok, null)
            .show()
        centerOk(d)
    }

    /** A single button, centred: hide the bar's spacer and let the button take the width. */
    private fun centerOk(d: AlertDialog) {
        val b = d.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val bar = b.parent as? ViewGroup ?: return
        for (i in 0 until bar.childCount) {
            val c = bar.getChildAt(i)
            if (c !== b) c.visibility = View.GONE
        }
        (b.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
            it.weight = 1f
            it.gravity = Gravity.CENTER
            b.layoutParams = it
        }
    }
}
