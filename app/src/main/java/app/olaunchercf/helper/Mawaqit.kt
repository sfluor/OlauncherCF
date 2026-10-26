package app.olaunchercf.helper

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import java.util.Locale

/**
 * Mawaqit has no public API, so to show the next prayer we host its square prayer
 * widget off-screen and read the text Mawaqit puts in it. The times are the mosque's
 * own and no internet is needed. If Mawaqit renames these views, nothing is shown.
 */
class MawaqitWidget(context: Context) {

    private val appContext = context.applicationContext
    private val widgetManager = AppWidgetManager.getInstance(appContext)
    private val host = object : AppWidgetHost(appContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView {
            return ReadingHostView(context)
        }
    }
    private var onUpdate: (String?) -> Unit = {}

    fun isBound(widgetId: Int): Boolean {
        return widgetManager.getAppWidgetInfo(widgetId)?.provider == PROVIDER
    }

    // returns the new widget id, and the intent asking for the user's permission if Android needs it
    fun bind(): Pair<Int, Intent?> {
        val widgetId = host.allocateAppWidgetId()
        if (widgetManager.bindAppWidgetIdIfAllowed(widgetId, PROVIDER)) return Pair(widgetId, null)

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, PROVIDER)
        return Pair(widgetId, intent)
    }

    fun unbind(widgetId: Int) {
        host.deleteAppWidgetId(widgetId)
    }

    // onUpdate gets the next prayer, e.g. "العصر ١٦:١٢", or null when there is nothing to show
    fun startListening(widgetId: Int, onUpdate: (String?) -> Unit) {
        this.onUpdate = onUpdate
        if (!isBound(widgetId)) {
            onUpdate(null)
            return
        }
        try {
            // creating the view applies Mawaqit's current content, listening brings the updates
            host.createView(appContext, widgetId, widgetManager.getAppWidgetInfo(widgetId))
            host.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
            onUpdate(null)
        }
    }

    fun stopListening() {
        onUpdate = {}
        try {
            host.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inner class ReadingHostView(context: Context) : AppWidgetHostView(context) {
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            onUpdate(readNextPrayer(this))
        }
    }

    private fun readNextPrayer(root: View): String? {
        val resources = try {
            appContext.packageManager.getResourcesForApplication(PACKAGE)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val label = textOf(root, resources, "sq_text_pray_name")
        return formatNextPrayer(
            name = label?.let { arabicPrayerName(it, prayerNames(resources)) },
            time = textOf(root, resources, "sq_text_pray_time"),
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun textOf(root: View, resources: Resources, idName: String): String? {
        val id = resources.getIdentifier(idName, "id", PACKAGE)
        if (id == 0) return null
        val view = root.findViewById<View>(id) as? TextView ?: return null
        return view.text?.toString()
    }

    // The label is in Mawaqit's own language (e.g. "Asr dans"), so we collect the prayer
    // names in every language Mawaqit has to find out which prayer it is.
    @SuppressLint("DiscouragedApi")
    private fun prayerNames(resources: Resources): Map<String, Int> {
        names?.let { return it }
        val found = HashMap<String, Int>()
        try {
            val ids = prayerNameIds.map { resources.getIdentifier(it, "string", PACKAGE) }
            val mawaqit = appContext.createPackageContext(PACKAGE, 0)
            for (tag in resources.assets.locales) {
                val config = Configuration(resources.configuration)
                config.setLocale(Locale.forLanguageTag(tag))
                val localized = mawaqit.createConfigurationContext(config).resources
                ids.forEachIndexed { index, id ->
                    if (id != 0) localized.getString(id).trim().ifEmpty { null }?.let { found[it] = index }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        names = found
        return found
    }

    companion object {
        const val PACKAGE = "com.kanout.mawaqit"
        private val PROVIDER = ComponentName(PACKAGE, "$PACKAGE.provider.SquareAppWidgetProvider")
        private const val HOST_ID = 2911

        // same order as arabicPrayerNames
        private val prayerNameIds = listOf(
            "first_pray_name", "second_pray_name", "third_pray_name", "forth_pray_name", "fifth_pray_name", "jumua",
        )

        // computed once per process, it means loading every language Mawaqit has
        private var names: Map<String, Int>? = null

        fun isInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}

private val arabicPrayerNames = arrayOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء", "الجمعة")

// names maps every prayer name Mawaqit knows, in all its languages, to its index in arabicPrayerNames.
// The longest name found in the label wins, e.g. "العصر" over "عصر".
fun arabicPrayerName(label: String, names: Map<String, Int>): String? {
    val name = names.keys.filter { label.contains(it) }.maxByOrNull { it.length } ?: return null
    return arabicPrayerNames[names.getValue(name)]
}

fun formatNextPrayer(name: String?, time: String?): String? {
    val cleanTime = time?.trim()
    if (cleanTime.isNullOrEmpty() || cleanTime.none { it.isDigit() }) return null
    return listOfNotNull(name, toArabicDigits(cleanTime)).joinToString(" ")
}
