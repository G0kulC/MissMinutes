package com.missminutes.agent

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Home screen: the dial, start and stop, brain and voice choices, keys, permissions and memory. */
class MainActivity : Activity() {

    private class PermissionRow(val label: String, val on: Boolean, val fix: () -> Unit)

    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private lateinit var dial: MinuteDialView
    private lateinit var statusLine: TextView
    private lateinit var startButton: TextView
    private lateinit var permsBox: LinearLayout
    private lateinit var memoryBox: LinearLayout
    private lateinit var claudeKey: EditText
    private lateinit var picoKey: EditText
    private lateinit var sarvamKey: EditText
    private lateinit var country: EditText
    private var testPlayer: VoicePlayer? = null

    private val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Palette.INK)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(8), dpi(22), dpi(48))
        }
        buildHero()
        buildBrain()
        buildVoice()
        buildBehaviour()
        buildKeys()
        buildPermissions()
        buildMemory()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { v, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top; bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                v.setPadding(0, top, 0, bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshPermissions()
        refreshMemory()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    override fun onDestroy() {
        scope.cancel()
        testPlayer?.shutdown()
        dial.stop()
        super.onDestroy()
    }

    // ---------- Sections ----------

    private fun buildHero() {
        dial = MinuteDialView(this)
        content.addView(dial, LinearLayout.LayoutParams(dpi(176), dpi(176)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpi(16)
        })
        content.addView(TextView(this).apply {
            text = "Miss Minutes"
            textSize = 34f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(Palette.CREAM)
            gravity = Gravity.CENTER
        })
        statusLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dpi(4), 0, dpi(20))
        }
        content.addView(statusLine)
        startButton = pillButton("Start Miss Minutes", filled = true) { toggleRunning() }
        content.addView(startButton, fullWidth())
        content.addView(pillButton("Talk now", filled = false) { talkNow() }, fullWidth(top = 10))
    }

    private fun buildBrain() {
        section("Brain", "The Claude model that thinks and acts for her. If one isn't available, she moves to the next.")
        val group = RadioGroup(this)
        BrainModel.entries.forEach { m ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = m.id
                text = twoLine(m.title, m.blurb)
                setTextColor(Palette.CREAM)
                textSize = 15f
                buttonTintList = ColorStateList.valueOf(Palette.AMBER)
                setPadding(dpi(8), dpi(10), 0, dpi(10))
                isChecked = prefs.model == m.id
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val id = g.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.model = id
        }
        content.addView(group)
    }

    private fun buildVoice() {
        section("Voice", "The Sarvam voice she speaks Tamil and English with.")
        val voices = Voices.sarvam
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voices)
            setSelection(voices.indexOf(prefs.sarvamSpeaker).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.sarvamSpeaker = voices[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillButton("Test voice", filled = false) { testVoice() })
        }
        content.addView(row)
    }

    private fun buildBehaviour() {
        section("Behaviour", null)
        content.addView(switchRow("Ask before sending messages or calling", prefs.confirmBeforeSending) { prefs.confirmBeforeSending = it })
        content.addView(switchRow("Search the web for live answers", prefs.webSearch) { prefs.webSearch = it })
        content.addView(switchRow("Keep listening after each reply", prefs.keepListening) { prefs.keepListening = it })
        content.addView(switchRow("Glow around the screen while she works", prefs.edgeGlow) { prefs.edgeGlow = it })

        content.addView(TextView(this).apply {
            text = "Wake word sensitivity. Higher wakes more easily but may trigger by mistake."
            textSize = 14f
            setTextColor(Palette.MUTED)
            setPadding(0, dpi(14), 0, dpi(4))
        })
        content.addView(SeekBar(this).apply {
            max = 60
            progress = ((prefs.wakeSensitivity - 0.3f) * 100).roundToInt().coerceIn(0, 60)
            progressTintList = ColorStateList.valueOf(Palette.AMBER)
            thumbTintList = ColorStateList.valueOf(Palette.AMBER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.wakeSensitivity = 0.3f + (seekBar?.progress ?: 35) / 100f
                    reloadService()
                }
            })
        })
    }

    private fun buildKeys() {
        section("Keys", "Stored encrypted on this phone. Never share the app with your keys inside.")
        claudeKey = field("Claude API key", prefs.claudeKey, secret = true)
        picoKey = field("Picovoice access key", prefs.picovoiceKey, secret = true)
        sarvamKey = field("Sarvam API key", prefs.sarvamKey, secret = true)
        country = field("Country code for phone numbers", prefs.countryCode, secret = false)
        content.addView(pillButton("Save keys", filled = false) {
            saveKeys()
            toast("Keys saved")
            reloadService()
        }, fullWidth(top = 14))
    }

    private fun buildPermissions() {
        section("Permissions", "Tap anything marked Turn on.")
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(permsBox)
        content.addView(TextView(this).apply {
            text = "If Android says Restricted setting: open App info for Miss Minutes, tap the ⋮ menu, choose Allow restricted settings, then try again."
            textSize = 13f
            setTextColor(Palette.MUTED)
            setPadding(0, dpi(8), 0, 0)
        })
    }

    private fun buildMemory() {
        section("Memory", "Things she remembers about you. Say \"remember that…\" to add more.")
        memoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(memoryBox)
        content.addView(pillButton("Clear memory", filled = false) {
            prefs.memories = emptyList()
            refreshMemory()
        }, fullWidth(top = 12))
    }

    // ---------- Actions ----------

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleRunning() {
        if (MissMinutesService.running) {
            startService(MissMinutesService.intent(this, MissMinutesService.ACTION_STOP))
        } else {
            saveKeys()
            if (!hasMic()) { requestPermissions(runtimePermissions, 1); toast("Allow the microphone, then tap Start"); return }
            startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_START))
        }
        statusLine.postDelayed({ refreshStatus() }, 700)
    }

    private fun talkNow() {
        saveKeys()
        if (!hasMic()) { requestPermissions(runtimePermissions, 1); return }
        startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_TALK))
        statusLine.postDelayed({ refreshStatus() }, 700)
    }

    private fun reloadService() {
        if (MissMinutesService.running) startService(MissMinutesService.intent(this, MissMinutesService.ACTION_RELOAD))
    }

    private fun saveKeys() {
        prefs.claudeKey = claudeKey.text.toString()
        prefs.picovoiceKey = picoKey.text.toString()
        prefs.sarvamKey = sarvamKey.text.toString()
        prefs.countryCode = country.text.toString().filter { it.isDigit() }.ifBlank { "91" }
    }

    private fun testVoice() {
        saveKeys()
        scope.launch {
            try {
                val player = testPlayer ?: VoicePlayer(this@MainActivity, SarvamClient(prefs)).also { testPlayer = it }
                dial.state = OrbState.SPEAKING
                player.onLevel = { dial.setLevel(it) }
                dial.start(withIntro = false)
                player.speak("வணக்கம்! I'm Miss Minutes. சொல்லுங்க, என்ன பண்ணணும்?", fallback = false)
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't play the voice")
            } finally {
                refreshStatus()
            }
        }
    }

    // ---------- Refresh ----------

    private fun refreshStatus() {
        val on = MissMinutesService.running
        statusLine.text = if (on) "Listening for \"${MissMinutesService.wakeWordName(this)}\""
                          else "Off. Tap Start to wake her up."
        startButton.text = if (on) "Stop Miss Minutes" else "Start Miss Minutes"
        dial.state = OrbState.IDLE
        dial.setLevel(0f)
        if (on) dial.start(withIntro = false) else dial.stop()
    }

    private fun refreshPermissions() {
        permsBox.removeAllViews()
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val askRuntime: () -> Unit = { requestPermissions(runtimePermissions, 1) }
        val rows = listOf(
            PermissionRow("Microphone", has(Manifest.permission.RECORD_AUDIO), askRuntime),
            PermissionRow("Contacts", has(Manifest.permission.READ_CONTACTS), askRuntime),
            PermissionRow("Phone calls", has(Manifest.permission.CALL_PHONE), askRuntime),
            PermissionRow("SMS", has(Manifest.permission.SEND_SMS), askRuntime),
            PermissionRow(
                "Notifications",
                Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
                askRuntime
            ),
            PermissionRow("Floating card over other apps", Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            PermissionRow("Accessibility, to use your apps", accessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            PermissionRow("Notification access, to read and reply", notificationAccessOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            PermissionRow("Keep running in the background", batteryUnrestricted()) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        )
        rows.forEachIndexed { i, row ->
            if (i > 0) permsBox.addView(hairline())
            permsBox.addView(permissionRow(row.label, row.on, row.fix))
        }
    }

    private fun refreshMemory() {
        memoryBox.removeAllViews()
        val items = prefs.memories
        if (items.isEmpty()) {
            memoryBox.addView(TextView(this).apply {
                text = "Nothing yet."
                textSize = 15f
                setTextColor(Palette.MUTED)
            })
            return
        }
        items.forEach { fact ->
            memoryBox.addView(TextView(this).apply {
                text = fact
                textSize = 15f
                setTextColor(Palette.CREAM)
                setPadding(0, dpi(6), 0, dpi(6))
            })
        }
    }

    private fun accessibilityOn() =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true

    private fun notificationAccessOn() = getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(this, NotificationReader::class.java))

    private fun batteryUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---------- Building blocks ----------

    private fun section(title: String, subtitle: String?) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 22f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(Palette.CREAM)
            setPadding(0, dpi(36), 0, dpi(2))
        })
        if (subtitle != null) {
            content.addView(TextView(this).apply {
                text = subtitle
                textSize = 14f
                setTextColor(Palette.MUTED)
                setPadding(0, 0, 0, dpi(8))
            })
        }
    }

    private fun twoLine(title: String, detail: String): CharSequence {
        val sb = SpannableStringBuilder(title).append('\n')
        val start = sb.length
        sb.append(detail)
        sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Palette.MUTED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) Palette.INK else Palette.CREAM)
        setPadding(dpi(20), dpi(14), dpi(20), dpi(14))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(99f)
            if (filled) setColor(Palette.AMBER)
            else { setColor(Color.TRANSPARENT); setStroke(dpi(1), Palette.alpha(Palette.CREAM, 70)) }
        }
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 50)), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top) }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Palette.CREAM)
        isChecked = checked
        setPadding(0, dpi(12), 0, dpi(12))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.AMBER, Palette.MUTED))
        trackTintList = ColorStateList(states, intArrayOf(Palette.alpha(Palette.AMBER, 120), Palette.alpha(Palette.MUTED, 80)))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun field(label: String, value: String, secret: Boolean): EditText {
        content.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Palette.MUTED)
            setPadding(0, dpi(14), 0, dpi(6))
        })
        val edit = EditText(this).apply {
            setText(value)
            setSingleLine()
            textSize = 15f
            setTextColor(Palette.CREAM)
            setHintTextColor(Palette.alpha(Palette.MUTED, 150))
            hint = if (secret) "Paste key here" else ""
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_PHONE
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(Palette.DIAL)
                setStroke(dpi(1), Palette.alpha(Palette.CREAM, 30))
            }
        }
        content.addView(edit)
        return edit
    }

    private fun permissionRow(label: String, on: Boolean, fix: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(14), 0, dpi(14))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Palette.CREAM)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = if (on) "On" else "Turn on"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (on) Palette.TEAL else Palette.AMBER)
        })
        if (!on) {
            isClickable = true
            background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, ColorDrawable(Color.WHITE))
            setOnClickListener { fix() }
        }
    }

    private fun hairline() = View(this).apply {
        setBackgroundColor(Palette.alpha(Palette.CREAM, 22))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
