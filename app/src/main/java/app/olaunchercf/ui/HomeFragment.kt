package app.olaunchercf.ui

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.CalendarContract
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.size
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olaunchercf.MainViewModel
import app.olaunchercf.R
import app.olaunchercf.data.AppModel
import app.olaunchercf.data.Constants
import app.olaunchercf.data.Constants.Action
import app.olaunchercf.data.Constants.AppDrawerFlag
import app.olaunchercf.data.Prefs
import app.olaunchercf.databinding.FragmentHomeBinding
import app.olaunchercf.helper.*
import app.olaunchercf.listener.OnSwipeTouchListener
import app.olaunchercf.listener.ViewSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date


private const val MAX_TODAY_EVENTS = 4
private const val MAX_TOMORROW_EVENTS = 3

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var vibrator: Vibrator
    private lateinit var mawaqit: MawaqitWidget
    private lateinit var islamicCornerParams: FrameLayout.LayoutParams
    private lateinit var hijriDateColors: ColorStateList

    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateHijriDate()
            updateEvents()
        }
    }

    private var calendarObserverRegistered = false
    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateEvents()
        }
    }

    private val fitEventsListener = ViewTreeObserver.OnGlobalLayoutListener { fitEventsBesideApps() }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        val view = binding.root
        prefs = Prefs(requireContext())

        if (prefs.firstSettingsOpen()) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        mawaqit = MawaqitWidget(requireContext())
        islamicCornerParams = binding.islamicLayout.layoutParams as FrameLayout.LayoutParams
        hijriDateColors = binding.hijriDate.textColors
        // the Arabic clock copies the clock, so it keeps its 12/24-hour format
        binding.clock.doAfterTextChanged { binding.arabicClock.text = toArabicDigits(it ?: "") }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(fitEventsListener)

        initObservers()

        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onStart() {
        super.onStart()
        if (prefs.showStatusBar) showStatusBar(requireActivity()) else hideStatusBar(requireActivity())

        binding.clock.textSize = prefs.textSize * 2.5f
        binding.date.textSize = prefs.textSize.toFloat()
        // Arabic script looks smaller than Latin at the same size
        binding.arabicClock.textSize = prefs.textSize * 2.5f
        binding.hijriDate.textSize = prefs.textSize * 1.3f
        binding.nextPrayer.textSize = prefs.textSize * 1.3f

        updateHijriDate()
        if (prefs.showHijriDate || prefs.showTodayEvents) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            requireContext().registerReceiver(timeReceiver, filter)
            timeReceiverRegistered = true
        }
        startNextPrayer()
    }

    override fun onStop() {
        if (timeReceiverRegistered) {
            requireContext().unregisterReceiver(timeReceiver)
            timeReceiverRegistered = false
        }
        if (calendarObserverRegistered) {
            requireContext().contentResolver.unregisterContentObserver(calendarObserver)
            calendarObserverRegistered = false
        }
        mawaqit.stopListening()
        super.onStop()
    }

    override fun onDestroyView() {
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(fitEventsListener)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()

        // here rather than in onStart, to catch the calendar permission granted from the dialog
        if (prefs.showTodayEvents && hasCalendarPermission(requireContext()) && !calendarObserverRegistered) {
            requireContext().contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
            calendarObserverRegistered = true
        }
        updateEvents()

        // only show "set as default"-button if tips are GONE
        if (binding.firstRunTips.visibility == View.GONE) {
            binding.setDefaultLauncher.visibility =
                if (isOlauncherDefault(requireContext())) View.GONE else View.VISIBLE
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.clock -> {
                when (val action = prefs.clickClockAction) {
                    Action.OpenApp -> openClickClockApp()
                    else -> handleOtherAction(action)
                }
            }
            R.id.date -> {
                when (val action = prefs.clickDateAction) {
                    Action.OpenApp -> openClickDateApp()
                    else -> handleOtherAction(action)
                }
            }
            R.id.setDefaultLauncher -> viewModel.resetDefaultLauncherApp(requireContext())
            else -> {
                try { // Launch app
                    val appLocation = view.id.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (prefs.homeLocked) return true

        val n = view.id
        val name = prefs.getHomeAppModel(n).appLabel
        showAppList(AppDrawerFlag.SetHomeApp, name.isNotEmpty(), n)
        return true
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.touchArea.setOnTouchListener(getHomeScreenGestureListener(context))
    }

    private fun initClickListeners() {
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
    }

    private fun initObservers() {
        with(viewModel) {
            clockAlignment.observe(viewLifecycleOwner) { gravity ->
                binding.dateTimeLayout.gravity = gravity.value()
                placeIslamicLayout(gravity)
            }
            homeAppsAlignment.observe(viewLifecycleOwner) { (gravity, onBottom) ->
                val horizontalAlignment = if (onBottom) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
                binding.homeAppsLayout.gravity = gravity.value() or horizontalAlignment

                binding.homeAppsLayout.children.forEach { view ->
                    (view as TextView).gravity = gravity.value()
                }
                placeEventsLayout(gravity)
            }
            homeAppsCount.observe(viewLifecycleOwner) {
                updateAppCount(it)
            }
            showTime.observe(viewLifecycleOwner) {
                binding.clock.visibility = if (it) View.VISIBLE else View.GONE
            }
            showDate.observe(viewLifecycleOwner) {
                binding.date.visibility = if (it) View.VISIBLE else View.GONE
            }
        }
    }

    // mirrors the clock: top-right corner when the clock is on the left, top-left when it is
    // on the right, under the date when it is centered. Left/right are absolute, like the clock's.
    @SuppressLint("RtlHardcoded")
    private fun placeIslamicLayout(gravity: Constants.Gravity) {
        val layout = binding.islamicLayout
        val corner = when (gravity) {
            Constants.Gravity.Left -> Gravity.RIGHT
            Constants.Gravity.Right -> Gravity.LEFT
            Constants.Gravity.Center -> null
        }
        val parent: ViewGroup = if (corner != null) binding.mainLayout else binding.dateTimeLayout
        if (layout.parent !== parent) {
            (layout.parent as ViewGroup).removeView(layout)
            if (corner != null) {
                parent.addView(layout, parent.indexOfChild(binding.dateTimeLayout) + 1, islamicCornerParams)
            } else {
                parent.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        if (corner != null) {
            islamicCornerParams.gravity = Gravity.TOP or corner
            layout.layoutParams = islamicCornerParams
        }
        layout.gravity = corner ?: gravity.value()
    }

    private fun updateHijriDate() {
        if (_binding == null) return
        if (prefs.showHijriDate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val hijri = hijriDate(prefs.hijriDateAdjustment)
            if (binding.hijriDate.text.toString() != hijri.text) binding.hijriDate.text = hijri.text
            if (isAroundWhiteDays(hijri.day)) {
                binding.hijriDate.setTextColor(ContextCompat.getColor(binding.hijriDate.context, R.color.hijriWhiteDays))
            } else {
                binding.hijriDate.setTextColor(hijriDateColors)
            }
            binding.hijriDate.visibility = View.VISIBLE
            binding.arabicClock.visibility = if (prefs.showTime) View.VISIBLE else View.GONE
        } else {
            binding.hijriDate.visibility = View.GONE
            binding.arabicClock.visibility = View.GONE
        }
    }

    // bottom-right corner, or bottom-left when the home apps are on the right
    @SuppressLint("RtlHardcoded")
    private fun placeEventsLayout(appsGravity: Constants.Gravity) {
        val params = binding.eventsLayout.layoutParams as FrameLayout.LayoutParams
        val side = if (appsGravity == Constants.Gravity.Right) Gravity.LEFT else Gravity.RIGHT
        params.gravity = Gravity.BOTTOM or side
        binding.eventsLayout.layoutParams = params
    }

    // Keeps the events clear of the home app labels beside them: the margin on the apps' side grows
    // past the widest of those labels, and the title column shrinks to fit. The events keep at least
    // a third of the screen.
    @SuppressLint("RtlHardcoded")
    private fun fitEventsBesideApps() {
        val binding = _binding ?: return
        val events = binding.eventsLayout
        if (events.visibility != View.VISIBLE) return
        val apps = binding.homeAppsLayout
        val labels = apps.children
            .filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE }
            .filter { apps.top + it.bottom > events.top && apps.top + it.top < events.bottom }
            .map { labelEdges(it, apps.left) }
            .toList()

        val width = binding.root.width
        val gap = dp2px(resources, 24)
        val maxMargin = width - gap - width / 3
        val params = events.layoutParams as FrameLayout.LayoutParams
        val onLeft = params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT
        val appsMargin = if (labels.isEmpty()) gap else {
            val appsEdge = if (onLeft) width - labels.minOf { it.first } else labels.maxOf { it.second }
            (appsEdge.toInt() + gap).coerceIn(gap, maxOf(gap, maxMargin))
        }
        val left = if (onLeft) gap else appsMargin
        val right = if (onLeft) appsMargin else gap
        if (params.leftMargin != left || params.rightMargin != right) {
            params.leftMargin = left
            params.rightMargin = right
            events.layoutParams = params
        }
    }

    // left and right edges of the label's text, or of its hint when it has no app yet
    @SuppressLint("RtlHardcoded")
    private fun labelEdges(label: TextView, offset: Int): Pair<Float, Float> {
        val shown = label.text.ifEmpty { label.hint ?: "" }
        val space = (label.width - label.totalPaddingLeft - label.totalPaddingRight).toFloat()
        val textWidth = label.paint.measureText(shown, 0, shown.length).coerceAtMost(space)
        val left = offset + label.left + label.totalPaddingLeft + when (Gravity.getAbsoluteGravity(label.gravity, label.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT -> space - textWidth
            Gravity.CENTER_HORIZONTAL -> (space - textWidth) / 2
            else -> 0f
        }
        return left to left + textWidth
    }

    private fun updateEvents() {
        if (_binding == null) return
        val context = requireContext().applicationContext
        if (!prefs.showTodayEvents || !hasCalendarPermission(context)) {
            binding.eventsLayout.visibility = View.GONE
            return
        }
        val timeFormat = DateFormat.getTimeFormat(context)
        val formatTime = { time: Long -> timeFormat.format(Date(time)) }
        val hidden = prefs.hiddenCalendars
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val today = startOfDay(now)
            val tomorrow = startOfDay(now, plusDays = 1)
            val (todayEvents, tomorrowEvents) = withContext(Dispatchers.IO) {
                queryDayEvents(context, today, hidden) to eventsStartingOn(tomorrow, queryDayEvents(context, tomorrow, hidden))
            }
            showEvents(
                dayEvents(todayEvents, today, now, MAX_TODAY_EVENTS, formatTime), today,
                dayEvents(tomorrowEvents, tomorrow, now, MAX_TOMORROW_EVENTS, formatTime), tomorrow,
            )
        }
    }

    // today's events, then tomorrow's under a separator
    private fun showEvents(today: DayEvents, startOfToday: Long, tomorrow: DayEvents, startOfTomorrow: Long) {
        val table = _binding?.eventsLayout ?: return
        table.removeAllViews()
        addDayEvents(table, today, startOfToday)
        if (tomorrow.rows.isNotEmpty()) {
            table.addView(daySeparator(getString(R.string.tomorrow), startOfTomorrow))
            addDayEvents(table, tomorrow, startOfTomorrow)
        }
        table.visibility = if (table.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun addDayEvents(table: TableLayout, day: DayEvents, startOfDay: Long) {
        for (row in day.rows) {
            val tableRow = eventRow(row.time ?: getString(R.string.all_day), row.title.ifBlank { getString(R.string.no_title) })
            tableRow.alpha = if (row.finished) 0.5f else 1f
            tableRow.setOnClickListener { openCalendarEvent(it.context, row.event) }
            table.addView(tableRow)
        }
        if (day.more > 0) {
            val tableRow = eventRow("", resources.getQuantityString(R.plurals.events_more, day.more, day.more))
            tableRow.setOnClickListener { openCalendarDay(it.context, startOfDay) }
            table.addView(tableRow)
        }
    }

    // a faint line across the list, ending with the day's name
    private fun daySeparator(label: String, startOfDay: Long): View {
        val context = requireContext()
        val labelView = TextView(context, null, 0, R.style.TextDefault).apply {
            textSize = prefs.textSize * 0.7f
            text = label
            setPaddingRelative(dp2px(resources, 8), 0, 0, 0)
        }
        // asks for no width, unlike a plain View, so the line only fills the width of the rows
        val line = object : View(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
            }
        }.apply {
            setBackgroundColor(labelView.currentTextColor)
            alpha = 0.5f
        }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.7f
            setPadding(0, dp2px(resources, 10), 0, dp2px(resources, 2))
            addView(line, LinearLayout.LayoutParams(0, maxOf(1, dp2px(resources, 1)), 1f))
            addView(labelView)
            setOnClickListener { openCalendarDay(it.context, startOfDay) }
        }
    }

    private fun eventRow(time: String, title: String): TableRow {
        val context = requireContext()
        val size = prefs.textSize * 0.85f
        val timeView = TextView(context, null, 0, R.style.TextDefault).apply {
            textSize = size
            text = time
        }
        val titleView = TextView(context, null, 0, R.style.TextDefault).apply {
            textSize = size
            text = title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = resources.displayMetrics.widthPixels / 2
            setPaddingRelative(dp2px(resources, 10), 0, 0, 0)
        }
        return TableRow(context).apply {
            addView(timeView)
            addView(titleView)
        }
    }

    private fun startNextPrayer() {
        binding.nextPrayer.visibility = View.GONE
        if (!prefs.showNextPrayer) return
        mawaqit.startListening(prefs.mawaqitWidgetId) { nextPrayer ->
            val view = _binding?.nextPrayer ?: return@startListening
            view.text = nextPrayer
            view.visibility = if (nextPrayer == null) View.GONE else View.VISIBLE
        }
    }

    private fun homeAppClicked(location: Int) {
        if (prefs.getAppName(location).isEmpty()) showLongPressToast()
        else launchApp(prefs.getHomeAppModel(location))
    }

    private fun launchApp(appModel: AppModel) {
        viewModel.selectedApp(appModel, AppDrawerFlag.LaunchApp)
    }

    private fun showAppList(flag: AppDrawerFlag, showHiddenApps: Boolean = false, n: Int = 0) {
        viewModel.getAppList(showHiddenApps)
        lifecycleScope.launch {
            try {
                findNavController().navigate(
                    R.id.action_mainFragment_to_appListFragment,
                    bundleOf("flag" to flag.toString(), "n" to n)
                )
            } catch (e: Exception) {
                findNavController().navigate(
                    R.id.appListFragment,
                    bundleOf("flag" to flag.toString())
                )
                e.printStackTrace()
            }
        }
    }

    private fun openSwipeRightApp() {
        if (prefs.appSwipeRight.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeRight)
        else openDialerApp(requireContext())
    }

    private fun openSwipeDownApp() {
        if (prefs.appSwipeDown.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeDown)
        else openDialerApp(requireContext())
    }

    private fun openSwipeUpApp() {
        if (prefs.appSwipeUp.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeUp)
        else showAppList(AppDrawerFlag.LaunchApp)
    }

    private fun openClickClockApp() {
        if (prefs.appClickClock.appPackage.isNotEmpty())
            launchApp(prefs.appClickClock)
        else openAlarmApp(requireContext())
    }

    private fun openClickDateApp() {
        if (prefs.appClickDate.appPackage.isNotEmpty())
            launchApp(prefs.appClickDate)
        else openCalendar(requireContext())
    }

    private fun openSwipeLeftApp() {
        if (prefs.appSwipeLeft.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeLeft)
        else openCameraApp(requireContext())
    }

    private fun openDoubleTapApp() {
        if (prefs.appDoubleTap.appPackage.isNotEmpty())
            launchApp(prefs.appDoubleTap)
        else openCameraApp(requireContext())
    }

    // This function handles all swipe actions that a independent of the actual swipe direction
    @SuppressLint("NewApi")
    private fun handleOtherAction(action: Action) {
        when(action) {
            Action.ShowNotification -> expandNotificationDrawer(requireContext())
            Action.LockScreen -> lockPhone()
            Action.ShowAppList -> showAppList(AppDrawerFlag.LaunchApp)
            Action.OpenApp -> {} // this should be handled in the respective onSwipe[Down,Right,Left] functions
            Action.OpenQuickSettings -> expandQuickSettings(requireContext())
            Action.ShowRecents -> initActionService(requireContext())?.showRecents()
            Action.Disabled -> {}
        }
    }

    private fun lockPhone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val actionService = ActionService.instance()
            if (actionService != null) {
                actionService.lockScreen()
            } else {
                openAccessibilitySettings(requireContext())
            }
        } else {
            requireActivity().runOnUiThread {
                try {
                    deviceManager.lockNow()
                } catch (e: SecurityException) {
                    showToastLong(requireContext(), "App does not have the permission to lock the device")
                } catch (e: Exception) {
                    showToastLong(requireContext(), "Olauncher failed to lock device.\nPlease check your app settings.")
                    prefs.lockModeOn = false
                }
            }
        }
    }

    private fun showLongPressToast() = showToastShort(requireContext(), "Long press to select app")

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getHomeScreenGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                when(val action = prefs.swipeLeftAction) {
                    Action.OpenApp -> openSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                when(val action = prefs.swipeRightAction) {
                    Action.OpenApp -> openSwipeRightApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                when(val action = prefs.swipeUpAction) {
                    Action.OpenApp -> openSwipeUpApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                when(val action = prefs.swipeDownAction) {
                    Action.OpenApp -> openSwipeDownApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    // viewModel.firstOpen(false)
                } catch (e: java.lang.Exception) {
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                when(val action = prefs.doubleTapAction) {
                    Action.OpenApp -> openDoubleTapApp()
                    else -> handleOtherAction(action)
                }
            }
        }
    }

    private fun getHomeAppsGestureListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
            
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                when(val action = prefs.swipeLeftAction) {
                    Action.OpenApp -> openSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                when(val action = prefs.swipeRightAction) {
                    Action.OpenApp -> openSwipeRightApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                when(val action = prefs.swipeUpAction) {
                    Action.OpenApp -> openSwipeUpApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                when(val action = prefs.swipeDownAction) {
                    Action.OpenApp -> openSwipeDownApp()
                    else -> handleOtherAction(action)
                }
            }
        }
    }

    // updates number of apps visible on home screen
    // does nothing if number has not changed
    private fun updateAppCount(newAppsNum: Int) {
        val oldAppsNum = binding.homeAppsLayout.size // current number
        val diff = oldAppsNum - newAppsNum

        if (diff in 1 until oldAppsNum) { // 1 <= diff <= oldNumApps
            binding.homeAppsLayout.children.drop(diff)
        } else if (diff < 0) {
            val alignment = prefs.homeAlignment.value() // make only one call to prefs and store here

            // add all missing apps to list
            for (i in oldAppsNum until newAppsNum) {
                val view = layoutInflater.inflate(R.layout.home_app_button, null) as TextView
                view.apply {
                    textSize = prefs.textSize.toFloat()
                    id = i
                    text = prefs.getHomeAppModel(i).appLabel
                    setOnTouchListener(getHomeAppsGestureListener(context, this))
                    if (!prefs.extendHomeAppsArea) {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    gravity = alignment
                }
                binding.homeAppsLayout.addView(view)
            }
        }
    }
}
