package dev.brahma.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import android.widget.Toast
import java.time.LocalDate
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var apps = emptyList<AppEntry>()
    private var home: HomeScreen? = null
    private enum class Screen { HOME, DRAWER, SETTINGS, EDITOR, LOCK, WALLPAPERS }
    private var screen = Screen.HOME
    private var batteryState: Intent? = null
    private var drawer: DrawerScreen? = null
    private val files by lazy { StickerFiles(this) }
    // Drawn home stickers by id, with the version they were drawn at.
    private val drawnCache = mutableMapOf<String, Pair<Long, Bitmap?>>()
    private var arranging = false
    private var editor: StickerEditor? = null
    private var editingNew = false
    private var editorId: String? = null
    private var designer: LockDesigner? = null
    private var wallpapers: WallpaperScreen? = null
    // Wallpaper thumbnails are drawn one at a time, so a long list never holds many big pictures at once.
    private val thumbnails by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val wallpaperFiles by lazy { WallpaperFiles(this) }
    private val luxe by lazy { LuxeStore(getSharedPreferences("luxe", MODE_PRIVATE)) }
    private var pendingExport: Bitmap? = null
    private var weatherLoading = false
    private var lockDrawing = false
    private val store by lazy { SettingsStore(getSharedPreferences("launcher", MODE_PRIVATE)) }
    private var settings = LauncherSettings()
    // The settings page that is open; null is the list of categories.
    private var settingsCategory: SettingsCategory? = null
    private var appliedDark = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) batteryState = intent
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = store.load()
        appliedDark = settings.theme.isDark(resources.configuration)
        // The window theme styles system widgets and dialogs; screens draw their own palettes.
        setTheme(if (appliedDark) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("screen")?.let { saved -> screen = Screen.entries.find { it.name == saved } ?: Screen.HOME }
        settingsCategory = SettingsCategory.entries.find { it.name == savedInstanceState?.getString("settings_category") }
        // A home app never exits on Back; Back closes settings or clears the home search.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::handleBack)
        }
    }

    // The manifest opts in to OnBackInvokedCallback, so this only runs below API 33.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Used below API 33 only")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        when (screen) {
            Screen.HOME -> if (home?.finishArranging(arranging) != true) home?.clearSearch()
            Screen.EDITOR -> closeEditor()
            Screen.LOCK, Screen.WALLPAPERS -> showSettings()
            Screen.SETTINGS -> if (settingsCategory != null) {
                settingsCategory = null
                showSettings()
            } else renderHome()
            else -> renderHome()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("settings_category", settingsCategory?.name)
        // Editors are not rebuilt after a restart; their callers' screen is.
        outState.putString("screen", if (screen == Screen.EDITOR || screen == Screen.LOCK || screen == Screen.WALLPAPERS) Screen.SETTINGS.name else screen.name)
    }

    // A theme switch needs a new window theme, so the activity is recreated on the same screen.
    private fun applyTheme(): Boolean {
        if (settings.theme.isDark(resources.configuration) == appliedDark) return false
        recreate()
        return true
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        batteryState = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        settings = store.load()
        if (applyTheme()) return
        // A focus session that ran out while Griha was away ends now, and its apps come back.
        luxe.load().let { data -> data.settle(System.currentTimeMillis(), java.time.ZoneId.systemDefault()).let { if (it != data) luxe.save(it) } }
        migrateLegacySticker()
        when (screen) {
            Screen.HOME -> renderHome(arranging)
            Screen.DRAWER -> drawer?.update(shownApps(), store.favorites()) ?: showDrawer()
            Screen.SETTINGS -> showSettings()
            // Keep unsaved edits when coming back from the share sheet or the save picker.
            Screen.EDITOR -> if (editor == null) showSettings()
            Screen.LOCK -> if (designer == null) showSettings()
            Screen.WALLPAPERS -> if (wallpapers == null) showSettings()
        }
        refreshWeather()
        refreshLockScreen()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) renderHome()
    }

    @Deprecated("Platform Activity result API; this app has no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_WALLPAPERS) {
            if (resultCode == RESULT_OK && data != null) importWallpapers(data)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == EXPORT_STICKER) pendingExport = null
            return
        }
        when (requestCode) {
            BACKUP_SETTINGS, ROLLBACK_SETTINGS -> {
                val ok = writeTo(uri) { it.write(SettingsBackup.export(this).toByteArray()) }
                Toast.makeText(this, if (ok) "Settings saved" else "Couldn't save the settings", Toast.LENGTH_SHORT).show()
                if (ok && requestCode == ROLLBACK_SETTINGS) pendingRollback?.let { createDocument(it.name, "application/vnd.android.package-archive", ROLLBACK_INSTALLER) }
            }
            ROLLBACK_INSTALLER -> {
                val apk = pendingRollback ?: return
                val ok = writeTo(uri) { output -> apk.inputStream().use { it.copyTo(output) } }
                if (!ok) return Toast.makeText(this, "Couldn't save the installer", Toast.LENGTH_SHORT).show()
                pendingRollback = null
                AlertDialog.Builder(this).setTitle("Remove this version")
                    .setMessage("Settings and installer are saved. Android's page for Griha opens next: tap Uninstall. Then open the " +
                        "saved installer from your Files app, make Griha your home app again, and in Settings, Updates and versions, " +
                        "tap Restore settings.")
                    .setPositiveButton("Open Android's page") { _, _ ->
                        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            RESTORE_SETTINGS -> {
                val json = try { contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } } catch (_: Exception) { null }
                if (json == null || !SettingsBackup.import(this, json)) {
                    return Toast.makeText(this, "That file isn't a Griha settings backup", Toast.LENGTH_LONG).show()
                }
                Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
                settings = store.load()
                settingsCategory = null
                recreate()
            }
            PICK_STICKER -> addSticker(uri)
            PICK_LOCK_PHOTO -> setLockPhoto(uri)
            EXPORT_STICKER -> {
                val bitmap = pendingExport ?: return
                pendingExport = null
                thread(name = "sticker-export") {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
                    } catch (_: Exception) {
                        false
                    }
                    runOnUiThread { Toast.makeText(this, if (ok) "Sticker saved" else "Couldn't save the sticker", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun refreshApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
        // The launcher lists itself too; opening it from the list goes to its settings.
        apps = AppCatalog.sorted(resolved
            .map {
                val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                AppEntry(component.flattenToString(), it.loadLabel(packageManager).toString(), component.packageName)
            })
    }

    private fun renderHome(arrange: Boolean = false) {
        val fromDrawer = screen == Screen.DRAWER
        // The all-apps search keeps the keyboard up; left alone, the keyboard and the space it held
        // linger over the new home as a fading ghost for a few seconds after all apps closes.
        if (fromDrawer) dismissKeyboard()
        screen = Screen.HOME
        settingsCategory = null
        arranging = arrange
        drawer = null
        editor = null
        designer = null
        wallpapers = null
        val favoriteIds = store.favorites()
        val shown = shownApps()
        val screen = HomeScreen(this, settings, appliedDark, shown,
            favorites = shown.filter { it.id in favoriteIds },
            shortcuts = AppCatalog.favorites(shown, settings.shortcuts),
            stickers = homeStickers(),
            arranging = arrange,
            weather = store.weather(),
            actions = HomeActions(
                onApp = ::launch,
                onAppOptions = ::appOptions,
                onAllApps = { showDrawer() },
                onSearch = { showDrawer(focusSearch = true) },
                onChooseFavorites = ::chooseFavorites,
                onSettings = ::showSettings,
                onClock = { open(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
                onCalendar = { open(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}"))) },
                onWebSearch = ::webSearch,
                onVoiceSearch = ::voiceSearch,
                onArrangeDone = { placed ->
                    val moved = placed.associateBy { it.id }
                    store.saveStickers(store.stickers().map { moved[it.id] ?: it })
                    renderHome()
                },
                luxe = LuxeHooks(luxe, onFocusChanged = { if (this@MainActivity.screen == Screen.HOME) renderHome() }, onCustomise = ::customiseLuxe)))
        home = screen
        show(screen, drawerMotion = if (fromDrawer) settings.drawerMotion else null, opening = false)
        updateStatus()
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        home = null
        drawer = null
        editor = null
        designer = null
        wallpapers = null
        val library = store.stickers().map { it to drawn(it) }
        show(SettingsScreen(this, settings, appliedDark, apps, library, settingsCategory, SettingsActions(
            onChange = { changed ->
                val restyled = settings.uiStyle != changed.uiStyle || settings.uiPalette(appliedDark) != changed.uiPalette(appliedDark)
                if (settings.lockScreen && !changed.lockScreen) clearLockScreen()
                settings = changed
                store.save(changed)
                if (applyTheme()) return@SettingsActions
                if (restyled) showSettings()
                refreshWeather()
                refreshLockScreen()
            },
            onAddSticker = {
                @Suppress("DEPRECATION")
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*"), PICK_STICKER)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                }
            },
            onEditSticker = { id -> openEditor(id, isNew = false) },
            onToggleSticker = { id, on -> store.saveStickers(store.stickers().map { if (it.id == id) it.copy(enabled = on) else it }) },
            onDeleteSticker = ::deleteSticker,
            onArrangeStickers = { renderHome(arrange = true) },
            onDesignLock = ::showLockDesigner,
            onHomeApp = { open(Intent(Settings.ACTION_HOME_SETTINGS)) },
            onWallpaper = ::showWallpapers,
            onCustomiseLuxe = ::customiseLuxe,
            onCategory = { settingsCategory = it; showSettings() },
            onDone = ::renderHome,
            updates = UpdateActions(
                check = { betas, done ->
                    thread(name = "update-check") {
                        val result = try { Updates.fetch(betas) to null } catch (e: Exception) {
                            null to "Couldn't reach GitHub. Check your connection and try again. (${e.message ?: e.javaClass.simpleName})"
                        }
                        runOnUiThread { done(result.first, result.second) }
                    }
                },
                install = ::installRelease,
                rollBack = ::rollBack,
                backUp = { createDocument("griha-settings.json", "application/json", BACKUP_SETTINGS) },
                restore = {
                    @Suppress("DEPRECATION")
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"), RESTORE_SETTINGS)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }))))
    }

    /** Puts [root] on screen; with [drawerMotion], all apps opens or closes over home in that way. */
    private fun show(root: View, drawerMotion: DrawerMotion? = null, opening: Boolean = true) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Insets add to the screen's own padding rather than replacing it.
            val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
                insets
            }
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (appliedDark) 0 else lightBars, lightBars)
        } else {
            root.fitsSystemWindows = true
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (appliedDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        // Home and drawer draw a scrim over the wallpaper; settings stays opaque.
        if (settings.wallpaper && (root is HomeScreen || root is DrawerScreen)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(settings.homePalette(appliedDark).background))
        }
        val frame = findViewById<ViewGroup>(android.R.id.content)
        val outgoing = frame.getChildAt(0)
        // Android's Remove animations setting turns this motion off too.
        val motion = drawerMotion?.takeIf { it != DrawerMotion.NONE && ValueAnimator.areAnimatorsEnabled() && frame.childCount == 1 }
        if (motion != null && outgoing != null) DrawerTransition.swap(frame, outgoing, root, motion, opening) else setContentView(root)
        root.requestApplyInsets()
        if (motion != null) {
            if (Effects.enabled(settings)) Effects.addPops(root)
        } else if (Effects.enabled(settings)) {
            Effects.addPops(root)
            Effects.blendIn(root)
            (root as? HomeScreen)?.animateIn()
        }
    }

    private fun updateStatus() {
        val screen = home ?: return
        val level = batteryState?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryState?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryState?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        screen.updateStatus(if (level >= 0 && scale > 0) level * 100 / scale else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun refreshWeather() {
        val place = settings.place.trim()
        if (!settings.weather || place.isEmpty() || weatherLoading) return
        val cached = store.weather()
        if (cached != null && cached.place == place && System.currentTimeMillis() - cached.fetchedAt < Weather.REFRESH_MILLIS) return
        weatherLoading = true
        val fahrenheit = Weather.usesFahrenheit(resources.configuration.locales[0])
        thread(name = "weather") {
            val result = try { Weather.fetch(place, fahrenheit) } catch (_: Exception) { null }
            runOnUiThread {
                weatherLoading = false
                // Drop a late answer if weather was turned off or the place changed meanwhile.
                if (result != null && !isDestroyed && settings.weather && settings.place.trim() == result.place) {
                    store.saveWeather(result)
                    home?.updateWeather(result)
                }
            }
        }
    }

    private fun webSearch(query: String) {
        try {
            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        } catch (_: ActivityNotFoundException) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")))
        }
    }

    private fun voiceSearch() {
        try {
            startActivity(Intent(RecognizerIntent.ACTION_WEB_SEARCH))
        } catch (_: ActivityNotFoundException) {
            open(Intent(Intent.ACTION_VOICE_COMMAND))
        }
    }

    private fun appOptions(app: AppEntry) {
        val isFavorite = app.id in store.favorites()
        AlertDialog.Builder(this).setTitle(app.label)
            .setItems(arrayOf(if (isFavorite) "Remove from favorites" else "Add to favorites", "App info")) { _, which ->
                if (which == 0) {
                    val ids = store.favorites().toMutableSet()
                    if (isFavorite) ids.remove(app.id) else ids.add(app.id)
                    store.saveFavorites(ids)
                    if (screen == Screen.DRAWER) drawer?.update(shownApps(), ids) else renderHome()
                } else {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseFavorites() {
        val chosen = store.favorites().toMutableSet()
        AlertDialog.Builder(this).setTitle(R.string.choose_favorites)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), BooleanArray(apps.size) { apps[it].id in chosen }) { _, which, checked ->
                if (checked) chosen += apps[which].id else chosen -= apps[which].id
            }
            .setPositiveButton(R.string.save) { _, _ ->
                store.saveFavorites(chosen)
                renderHome()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showDrawer(focusSearch: Boolean = false) {
        val fromHome = screen == Screen.HOME
        screen = Screen.DRAWER
        home = null
        val view = DrawerScreen(this, settings.homePalette(appliedDark), settings.iconKit(settings.homePalette(appliedDark)), settings.wallpaper,
            Effects.enabled(settings), shownApps(),
            store.favorites(), "", settings.swipeDistance, DrawerActions(
                onApp = ::launch,
                onAppOptions = ::appOptions,
                onHome = ::renderHome))
        drawer = view
        show(view, drawerMotion = if (fromHome) settings.drawerMotion else null)
        if (focusSearch) view.search.post {
            view.search.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(view.search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---- Updates ----

    /** The older installer waiting to be saved during a rollback. */
    private var pendingRollback: java.io.File? = null

    private fun createDocument(name: String, type: String, request: Int) {
        @Suppress("DEPRECATION")
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type).putExtra(Intent.EXTRA_TITLE, name), request)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeTo(uri: Uri, write: (java.io.OutputStream) -> Unit) = try {
        contentResolver.openOutputStream(uri, "wt")?.use(write) != null
    } catch (_: Exception) {
        false
    }

    /** Downloads and checks [release]'s installer, then [then] gets it on the main thread. */
    private fun fetchInstaller(release: Release, then: (java.io.File) -> Unit) {
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Downloading Griha ${release.version}").setView(progress)
            .setCancelable(false).show()
        thread(name = "update-download") {
            val result: Pair<java.io.File?, String?> = try {
                val file = Updates.download(this, release) { percent -> runOnUiThread { progress.progress = percent } }
                val sum = Updates.sha256(file)
                when {
                    release.sha256 == null -> null to "This release has no published checksum, so Griha won't install it."
                    sum != release.sha256 -> null to "The download doesn't match its published checksum and was thrown away."
                    Updates.sameSigner(this, file) == false -> null to "This installer isn't signed with the same key as your Griha, so Android would refuse it."
                    else -> file to null
                }.also { (ok, _) -> if (ok == null) file.delete() }
            } catch (e: Exception) {
                null to "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                dialog.dismiss()
                val file = result.first
                if (file == null) AlertDialog.Builder(this).setTitle("Not installed").setMessage(result.second)
                    .setPositiveButton(android.R.string.ok, null).show()
                else then(file)
            }
        }
    }

    private fun installRelease(release: Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Griha to install updates, then tap Install again", Toast.LENGTH_LONG).show()
            return open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        fetchInstaller(release) { apk ->
            try {
                Updates.install(this, apk)
                Toast.makeText(this, "Installing Griha ${release.version}. Home restarts when it is done.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't start the install: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Android refuses to install an older version over a newer one, so a rollback saves settings and
    // the installer to files first, then the newer version is removed and the older one installed.
    private fun rollBack(release: Release) {
        AlertDialog.Builder(this).setTitle("Roll back to Griha ${release.version}?")
            .setMessage("Android installs an older version only after the newer one is removed, which also clears Griha's " +
                "settings. Griha will:\n\n1. Download and check version ${release.version}.\n2. Save your settings to a file you choose." +
                "\n3. Save the installer to a file you choose.\n4. Open Android's page to remove this version.\n\nThen install the " +
                "saved file and restore your settings in Settings, Updates and versions. Sticker and wallpaper pictures are not kept.")
            .setPositiveButton("Start") { _, _ ->
                fetchInstaller(release) { apk ->
                    pendingRollback = apk
                    createDocument("griha-settings.json", "application/json", ROLLBACK_SETTINGS)
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun dismissKeyboard() {
        val focused = currentFocus
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow((focused ?: window.decorView).windowToken, 0)
        focused?.clearFocus()
    }

    // ---- Stickers ----

    private fun drawn(sticker: Sticker): Bitmap? {
        drawnCache[sticker.id]?.let { (version, bitmap) -> if (version == sticker.version) return bitmap }
        val bitmap = files.load(files.drawn(sticker.id), DRAWN_SIDE)
        drawnCache[sticker.id] = sticker.version to bitmap
        return bitmap
    }

    private fun homeStickers() = store.stickers().filter { it.enabled }.mapNotNull { sticker -> drawn(sticker)?.let { sticker to it } }

    /** Pictures chosen before the sticker library become its first, plain sticker. */
    private fun migrateLegacySticker() {
        val legacy = settings.sticker ?: return
        val uri = Uri.parse(legacy)
        if (store.stickers().isEmpty()) StickerFiles.decode(this, uri)?.let { photo ->
            val sticker = Sticker(Sticker.newId(), subject = false, version = System.currentTimeMillis())
            files.save(files.source(sticker.id), photo)
            files.save(files.drawn(sticker.id), StickerArt.render(photo, null, emptyList()).bitmap)
            store.saveStickers(listOf(sticker))
        }
        try {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Grant already gone.
        }
        settings = settings.copy(sticker = null)
        store.save(settings)
    }

    // Copies the picked photo into the app, so the sticker survives the original being deleted.
    private fun addSticker(uri: Uri) {
        Toast.makeText(this, "Finding the subject…", Toast.LENGTH_SHORT).show()
        thread(name = "sticker-add") {
            val photo = StickerFiles.decode(this, uri)
            val id = Sticker.newId()
            if (photo != null) files.save(files.source(id), photo)
            runOnUiThread {
                if (photo == null) Toast.makeText(this, R.string.sticker_unavailable, Toast.LENGTH_SHORT).show()
                else openEditor(id, isNew = true)
            }
        }
    }

    /** Opens the editor, finding the subject first whenever no cutout is stored yet. */
    private fun openEditor(id: String, isNew: Boolean) {
        thread(name = "sticker-open") {
            val source = files.load(files.source(id))
            val mask = files.load(files.mask(id))
            val auto = files.load(files.auto(id))
            runOnUiThread {
                if (source == null) return@runOnUiThread Toast.makeText(this, R.string.sticker_unavailable, Toast.LENGTH_SHORT).show()
                val existing = store.stickers().find { it.id == id }
                if (mask != null) return@runOnUiThread showEditor(existing ?: Sticker(id), source, auto ?: mask, mask, null, isNew)
                Subjects.find(source) { result ->
                    if (isFinishing || isDestroyed) return@find
                    when (result) {
                        is Subjects.Result.Found -> {
                            val found = result.mask
                            val copy = found.copy(Bitmap.Config.ARGB_8888, false)
                            thread(name = "sticker-mask") {
                                files.save(files.auto(id), copy)
                                files.save(files.mask(id), copy)
                            }
                            val start = existing?.copy(subject = true) ?: Sticker(id, effects = Sticker.startingEffects)
                            showEditor(start, source, copy, found, null, isNew)
                        }
                        else -> {
                            val note = when (result) {
                                Subjects.Result.NoSubject -> "No clear subject in this photo, so the whole picture is used."
                                Subjects.Result.Downloading -> "The subject finder is still downloading through Play services. " +
                                    "The whole picture is used for now; open Edit later to cut out the subject."
                                is Subjects.Result.Failed -> "The subject finder isn't available on this phone, so the whole picture is used."
                                else -> null
                            }
                            showEditor((existing ?: Sticker(id)).copy(subject = false), source, null, null, note, isNew)
                        }
                    }
                }
            }
        }
    }

    private fun showEditor(sticker: Sticker, source: Bitmap, auto: Bitmap?, mask: Bitmap?, note: String?, isNew: Boolean) {
        screen = Screen.EDITOR
        home = null
        drawer = null
        editingNew = isNew
        editorId = sticker.id
        val view = StickerEditor(this, settings.uiPalette(appliedDark), sticker, source, auto, mask, note, StickerEditorActions(
            onSave = ::saveSticker,
            onExport = { edited, editedMask -> fullSticker(edited, editedMask) { bitmap ->
                pendingExport = bitmap
                @Suppress("DEPRECATION")
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/png").putExtra(Intent.EXTRA_TITLE, "brahmaos-sticker.png"), EXPORT_STICKER)
                } catch (_: ActivityNotFoundException) {
                    pendingExport = null
                    Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                }
            } },
            onShare = { edited, editedMask -> fullSticker(edited, editedMask) { bitmap -> shareSticker(edited.id, bitmap) } },
            onCancel = ::closeEditor))
        editor = view
        show(view)
    }

    private fun closeEditor() {
        val id = editorId
        // A new sticker that was never saved leaves no files behind.
        if (editingNew && id != null && store.stickers().none { it.id == id }) thread(name = "sticker-discard") { files.delete(id) }
        editor = null
        editorId = null
        editingNew = false
        showSettings()
    }

    private fun saveSticker(sticker: Sticker, mask: Bitmap?) {
        val updated = sticker.copy(version = System.currentTimeMillis())
        val maskCopy = mask?.copy(Bitmap.Config.ARGB_8888, false)
        thread(name = "sticker-save") {
            maskCopy?.let { files.save(files.mask(updated.id), it) }
            val source = files.load(files.source(updated.id), DRAWN_SIDE)
            if (source != null) {
                val edge = maskCopy?.takeIf { updated.subject }?.let {
                    StickerArt.softEdge(Bitmap.createScaledBitmap(it, source.width, source.height, true), updated.softness)
                }
                files.save(files.drawn(updated.id), StickerArt.render(source, edge, updated.effects).bitmap)
            }
            runOnUiThread {
                val library = store.stickers()
                store.saveStickers(if (library.any { it.id == updated.id }) library.map { if (it.id == updated.id) updated else it } else library + updated)
                editingNew = false
                editor = null
                editorId = null
                showSettings()
            }
        }
    }

    /** Draws the sticker at the photo's full stored size for saving or sharing. */
    private fun fullSticker(sticker: Sticker, mask: Bitmap?, done: (Bitmap) -> Unit) {
        val maskCopy = mask?.copy(Bitmap.Config.ARGB_8888, false)
        Toast.makeText(this, "Preparing the sticker…", Toast.LENGTH_SHORT).show()
        thread(name = "sticker-full") {
            val source = files.load(files.source(sticker.id)) ?: return@thread
            val edge = maskCopy?.takeIf { sticker.subject }?.let { StickerArt.softEdge(it, sticker.softness) }
            val bitmap = StickerArt.render(source, edge, sticker.effects).bitmap
            runOnUiThread { done(bitmap) }
        }
    }

    private fun shareSticker(id: String, bitmap: Bitmap) {
        thread(name = "sticker-share") {
            val file = java.io.File(StickerShareProvider.shareFolder(cacheDir), "sticker-$id.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = StickerShareProvider.uri(packageName, file)
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri("", uri)
                open(Intent.createChooser(send, "Share sticker"))
            }
        }
    }

    private fun deleteSticker(id: String) {
        store.saveStickers(store.stickers().filter { it.id != id })
        val design = store.lockDesign()
        if (design.stickerId == id) store.saveLockDesign(design.copy(stickerId = null))
        drawnCache.remove(id)
        thread(name = "sticker-delete") { files.delete(id) }
        showSettings()
    }

    /** Every app except those a running focus session hides. */
    private fun shownApps(): List<AppEntry> {
        val hidden = luxe.load().hidden(System.currentTimeMillis())
        return if (hidden.isEmpty()) apps else apps.filter { it.id !in hidden }
    }

    // ---- Luxe home ----

    private fun customiseLuxe() {
        AlertDialog.Builder(this).setTitle("Customise Luxe home")
            .setItems(arrayOf("Cards to show", "Apps hidden during focus", "Quote topics")) { _, which ->
                when (which) {
                    0 -> pickMany("Cards to show", LuxeCard.entries.map { it.title }, LuxeCard.entries.map { it in luxe.load().cards }) { picked ->
                        luxe.save(luxe.load().copy(cards = LuxeCard.entries.filterIndexed { index, _ -> picked[index] }.toSet()))
                    }
                    1 -> {
                        val data = luxe.load()
                        // While a session runs its list is fixed, so it cannot be cut short from here.
                        if (data.focusActive(System.currentTimeMillis())) {
                            return@setItems Toast.makeText(this, "Locked until the focus session ends", Toast.LENGTH_SHORT).show()
                        }
                        val choices = apps.filter { it.packageName != packageName }
                        pickMany("Apps hidden during focus", choices.map { it.label }, choices.map { it.id in data.focusApps }) { picked ->
                            luxe.save(luxe.load().copy(focusApps = choices.filterIndexed { index, _ -> picked[index] }.map { it.id }.toSet()))
                        }
                    }
                    else -> pickMany("Quote topics", Quotes.topics, Quotes.topics.map { it in luxe.load().quoteTopics }) { picked ->
                        luxe.save(luxe.load().copy(quoteTopics = Quotes.topics.filterIndexed { index, _ -> picked[index] }.toSet()))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun pickMany(title: String, labels: List<String>, checked: List<Boolean>, onSave: (BooleanArray) -> Unit) {
        val picked = checked.toBooleanArray()
        AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), picked) { _, index, on -> picked[index] = on }
            .setPositiveButton("Save") { _, _ ->
                onSave(picked)
                if (screen == Screen.HOME) renderHome()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---- Wallpapers ----

    private fun showWallpapers() {
        screen = Screen.WALLPAPERS
        home = null
        drawer = null
        editor = null
        designer = null
        val size = screenSize()
        val dark = appliedDark
        val view = WallpaperScreen(this, settings.uiPalette(dark), settings.layout, wallpaperFiles.list(),
            size.second / size.first.toFloat(), WallpaperActions(
                thumbnail = { source, width, height, done ->
                    thumbnails.execute {
                        val bitmap = try {
                            drawWallpaper(source, dark, width, height)
                        } catch (_: Exception) {
                            null
                        }
                        runOnUiThread { if (screen == Screen.WALLPAPERS) done(bitmap) }
                    }
                },
                onPick = ::pickWallpaper,
                onImport = {
                    @Suppress("DEPRECATION")
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/zip", "application/x-zip-compressed"))
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_WALLPAPERS)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                onSystemPicker = { open(Intent(Intent.ACTION_SET_WALLPAPER)) },
                onDone = ::showSettings))
        wallpapers = view
        show(view)
    }

    private fun drawWallpaper(source: WallpaperSource, dark: Boolean, width: Int, height: Int): Bitmap? = when (source) {
        is WallpaperSource.Theme -> ThemeWallpapers.render(this, source.layout, dark, source.kind, width, height)
        is WallpaperSource.Imported -> WallpaperFiles.load(source.file, maxOf(width, height))
    }

    private fun pickWallpaper(source: WallpaperSource) {
        // Griha's own lock design redraws the lock screen, so the lock choice is offered only when that is off.
        val choices = mutableListOf("Home screen")
        if (!settings.lockScreen) choices += "Home and lock screen"
        if (source is WallpaperSource.Imported) choices += "Remove from Griha"
        AlertDialog.Builder(this).setTitle("Use this wallpaper")
            .setItems(choices.toTypedArray()) { _, which ->
                when (choices[which]) {
                    "Remove from Griha" -> AlertDialog.Builder(this).setTitle("Remove this wallpaper?")
                        .setMessage("It is deleted from Griha. A wallpaper already set stays until you change it.")
                        .setPositiveButton("Remove") { _, _ ->
                            wallpaperFiles.delete((source as WallpaperSource.Imported).file)
                            showWallpapers()
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                    else -> setWallpaper(source, lock = choices[which] == "Home and lock screen")
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun setWallpaper(source: WallpaperSource, lock: Boolean) {
        val size = screenSize()
        val dark = appliedDark
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show()
        thread(name = "wallpaper") {
            val ok = try {
                val bitmap = when (source) {
                    is WallpaperSource.Theme -> ThemeWallpapers.render(this, source.layout, dark, source.kind, size.first, size.second)
                    is WallpaperSource.Imported -> WallpaperFiles.load(source.file, maxOf(size.first, size.second))
                }
                val which = WallpaperManager.FLAG_SYSTEM or if (lock) WallpaperManager.FLAG_LOCK else 0
                bitmap != null && WallpaperManager.getInstance(this).setBitmap(bitmap, null, true, which) != 0
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!ok) return@runOnUiThread Toast.makeText(this, "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show()
                // A wallpaper only shows behind home when home lets it through.
                if (!settings.wallpaper) {
                    settings = settings.copy(wallpaper = true)
                    store.save(settings)
                    Toast.makeText(this, "Wallpaper set, and turned on behind home", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importWallpapers(data: Intent) {
        val uris = buildList {
            data.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add) }
            if (isEmpty()) data.data?.let(::add)
        }
        if (uris.isEmpty()) return
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        val side = screenSize().let { maxOf(it.first, it.second) }
        thread(name = "wallpaper-import") {
            val result = wallpaperFiles.import(uris, side)
            runOnUiThread {
                val parts = mutableListOf("${result.added} added")
                if (result.skipped > 0) parts += "${result.skipped} skipped"
                if (result.full) parts += "limit of ${WallpaperFiles.MAX_COUNT} reached"
                Toast.makeText(this, parts.joinToString(", "), Toast.LENGTH_LONG).show()
                if (screen == Screen.WALLPAPERS) showWallpapers()
            }
        }
    }

    // ---- Lock designer ----

    private fun showLockDesigner() {
        screen = Screen.LOCK
        wallpapers = null
        home = null
        drawer = null
        editor = null
        val size = screenSize()
        val view = LockDesigner(this, settings.uiPalette(appliedDark), store.lockDesign(), store.stickers(),
            size.second / size.first.toFloat(), LockDesignerActions(
                onChange = store::saveLockDesign,
                preview = { design, done ->
                    val sticker = lockSticker(design)
                    val drawnSettings = settings
                    val dark = appliedDark
                    thread(name = "lock-preview") {
                        val bitmap = LockScreen.render(this, drawnSettings, dark, size.first / 3, size.second / 3, design, sticker)
                        runOnUiThread { done(bitmap) }
                    }
                },
                onPickPhoto = {
                    @Suppress("DEPRECATION")
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("image/*"), PICK_LOCK_PHOTO)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                onRemovePhoto = ::removeLockPhoto,
                onApply = ::applyLockDesign,
                onDone = ::showSettings))
        designer = view
        show(view)
    }

    private fun applyLockDesign() {
        if (settings.lockScreen) {
            store.saveLockKey(null)
            refreshLockScreen(announce = true)
            return
        }
        AlertDialog.Builder(this).setTitle("Replace lock-screen wallpaper?")
            .setMessage("Your current lock-screen picture will be replaced and Griha cannot bring it back. " +
                "Turning this off later makes the lock screen use your home wallpaper.")
            .setPositiveButton("Replace") { _, _ ->
                settings = settings.copy(lockScreen = true)
                store.save(settings)
                refreshLockScreen(announce = true)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun launch(app: AppEntry, from: View?) {
        val component = ComponentName.unflattenFromString(app.id) ?: return
        if (component.packageName == packageName) return showSettings()
        if (app.id in luxe.load().hidden(System.currentTimeMillis())) {
            return Toast.makeText(this, "Hidden until your focus session ends", Toast.LENGTH_SHORT).show()
        }
        open(Intent.makeMainActivity(component), if (Effects.enabled(settings)) Effects.openFrom(from) else null)
    }

    // Draws the themed lock wallpaper off the main thread when what it shows has changed.
    private fun screenSize() = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    // The lock photo stands in for a sticker whose photo is used; it has no effects of its own.
    private fun lockSticker(design: LockDesign): Sticker? {
        val photoBackground = design.background == LockBackground.PHOTO || design.background == LockBackground.BLURRED ||
            design.background == LockBackground.GENERATED
        if (photoBackground && design.photoId != null) return Sticker(design.photoId)
        return design.stickerId?.let { id -> store.stickers().find { it.id == id } }
    }

    // Keeps the picked photo in the app and finds its subject, so the depth effect can use it.
    private fun setLockPhoto(uri: Uri) {
        Toast.makeText(this, "Finding the subject…", Toast.LENGTH_SHORT).show()
        thread(name = "lock-photo") {
            val photo = StickerFiles.decode(this, uri)
            val id = Sticker.newId()
            if (photo != null) files.save(files.source(id), photo)
            runOnUiThread {
                if (photo == null) return@runOnUiThread Toast.makeText(this, R.string.sticker_unavailable, Toast.LENGTH_SHORT).show()
                Subjects.find(photo) { result ->
                    if (isFinishing || isDestroyed) return@find
                    if (result is Subjects.Result.Found) {
                        val copy = result.mask.copy(Bitmap.Config.ARGB_8888, false)
                        thread(name = "lock-photo-mask") { files.save(files.mask(id), copy) }
                    } else {
                        Toast.makeText(this, "No subject found, so the whole photo is used.", Toast.LENGTH_SHORT).show()
                    }
                    val old = store.lockDesign()
                    old.photoId?.let { files.delete(it) }
                    val keep = old.background == LockBackground.PHOTO || old.background == LockBackground.BLURRED || old.background == LockBackground.GENERATED
                    store.saveLockDesign(old.copy(custom = true, photoId = id, photoVersion = System.currentTimeMillis(),
                        background = if (keep) old.background else LockBackground.PHOTO))
                    if (screen == Screen.LOCK) showLockDesigner()
                }
            }
        }
    }

    private fun removeLockPhoto() {
        val design = store.lockDesign()
        design.photoId?.let { id -> thread(name = "lock-photo-delete") { files.delete(id) } }
        store.saveLockDesign(design.copy(photoId = null, photoVersion = 0))
        showLockDesigner()
    }

    private fun refreshLockScreen(announce: Boolean = false) {
        if (!settings.lockScreen || lockDrawing) return
        val design = store.lockDesign()
        val sticker = lockSticker(design)
        val key = LockScreen.key(settings, appliedDark, LocalDate.now(), design, sticker)
        if (store.lockKey() == key) return
        lockDrawing = true
        val size = screenSize()
        val drawn = settings
        val dark = appliedDark
        thread(name = "lock-screen") {
            val ok = try {
                val bitmap = LockScreen.render(this, drawn, dark, size.first, size.second, design, sticker)
                WallpaperManager.getInstance(this).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                lockDrawing = false
                // Turned off while drawing: leave the key unset so the cleared state holds.
                if (ok && settings.lockScreen) store.saveLockKey(key) else if (ok) clearLockScreen()
                if (!ok) Toast.makeText(this, "Couldn't set the lock screen", Toast.LENGTH_SHORT).show()
                else if (announce) Toast.makeText(this, "Lock screen updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearLockScreen() {
        thread(name = "lock-screen-clear") {
            try {
                WallpaperManager.getInstance(this).clear(WallpaperManager.FLAG_LOCK)
            } catch (_: Exception) {
                // Nothing to undo if the system refuses; the toggle is off either way.
            }
        }
    }

    private fun open(intent: Intent, options: Bundle? = null) {
        try {
            startActivity(intent, options)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            refreshApps()
            when (screen) {
                Screen.HOME -> renderHome()
                Screen.DRAWER -> drawer?.update(shownApps(), store.favorites())
                Screen.SETTINGS, Screen.EDITOR, Screen.LOCK, Screen.WALLPAPERS -> Unit
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int) = (this as Context).dp(value)

    private companion object {
        const val PICK_STICKER = 1
        const val EXPORT_STICKER = 2
        const val PICK_LOCK_PHOTO = 3
        const val PICK_WALLPAPERS = 4
        const val BACKUP_SETTINGS = 5
        const val RESTORE_SETTINGS = 6
        const val ROLLBACK_SETTINGS = 7
        const val ROLLBACK_INSTALLER = 8
        const val DRAWN_SIDE = 1024
    }
}
