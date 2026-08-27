package com.billtt.riddle

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var diaryView: DiaryView
    private lateinit var controller: DiaryController
    private lateinit var prefs: Prefs
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        diaryView = DiaryView(this)
        controller = DiaryController(this, diaryView, prefs)
        setContentView(diaryView)

        // Long-press with a finger -> settings; any touch during linger -> skip the wait.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                showSettingsDialog()
            }
        })
        diaryView.setOnTouchListener { _, event -> handleTouch(event) }

        // Attaching TouchHelper requires the window to have focus (so the view position is final);
        // see onWindowFocusChanged.
    }

    private var penAttached = false

    private fun tryAttachPen() {
        if (penAttached || !::controller.isInitialized || !hasWindowFocus()) return
        penAttached = true
        controller.attach()
        if (!prefs.configured) showSettingsDialog()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        // Two fingers down at once = send this page now, without waiting out the idle delay.
        // Deliberately not a double-tap: a single stray finger is far too easy to produce
        // while a hand is resting on the page.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            if (controller.triggerNow()) return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            controller.requestSkipLinger()
        }
        // Debug fallback for non-BOOX environments: simulate strokes with touch events.
        if (controller.debugTouchFallback &&
            (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)
        ) {
            controller.debugAddPoint(
                event.x, event.y, event.pressure.coerceIn(0.1f, 1f) * DiaryController.MAX_PRESSURE,
                up = event.actionMasked == MotionEvent.ACTION_UP,
            )
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (::controller.isInitialized) controller.onResume()
    }

    /**
     * Attach the pen driver when the window first gains focus (only then is the view's
     * screen position final); on every subsequent focus gain (dialog / IME dismissed)
     * resume writing.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::controller.isInitialized) return
        hideSystemUi()
        // hideSystemUi() requests a relayout; bind on the next frame, once it has settled.
        if (penAttached) controller.onResume() else diaryView.post { tryAttachPen() }
    }

    override fun onPause() {
        super.onPause()
        if (::controller.isInitialized) controller.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) controller.onDestroy()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            // Without these two the content is laid out INSIDE the bars and then resized
            // when they hide. The pen's limit rect is bound from that geometry, so the
            // resize is what produced "Empty region detected when mapping" and silent
            // callbacks. Full-bleed from the start means the geometry never moves.
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // ------------------------------------------------------------- settings UI

    private fun showSettingsDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        var profiles = prefs.profiles.toMutableList()
        var current = prefs.activeIndex.coerceIn(0, profiles.size - 1)

        val spinner = Spinner(this)
        val nameInput = EditText(this).apply { hint = getString(R.string.settings_name_hint) }
        val baseUrlInput = EditText(this).apply {
            hint = getString(R.string.settings_base_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val modelInput = EditText(this).apply {
            hint = getString(R.string.settings_model_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val keyInput = EditText(this).apply {
            hint = getString(R.string.settings_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val personaInput = EditText(this).apply {
            hint = getString(R.string.settings_persona_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
        }
        val idleInput = EditText(this).apply {
            hint = getString(R.string.settings_idle_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.1f".format(prefs.idleMs / 1000.0))
        }
        val memoryToggle = CheckBox(this).apply {
            text = getString(R.string.settings_memory)
            isChecked = prefs.memoryEnabled
        }

        fun loadInto(i: Int) {
            val p = profiles[i]
            nameInput.setText(p.name)
            baseUrlInput.setText(p.baseUrl)
            modelInput.setText(p.model)
            keyInput.setText(p.key)
            personaInput.setText(p.persona)
        }

        /** Pull the on-screen fields back into the profile they belong to. */
        fun captureInto(i: Int) {
            profiles[i] = Prefs.Profile(
                name = nameInput.text.toString().trim().ifEmpty { "档案 ${i + 1}" },
                baseUrl = baseUrlInput.text.toString().trim(),
                model = modelInput.text.toString().trim(),
                key = keyInput.text.toString().trim(),
                persona = personaInput.text.toString().trim(),
            )
        }

        fun refreshSpinner(select: Int) {
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                profiles.map { it.name },
            )
            spinner.setSelection(select)
        }

        refreshSpinner(current)
        loadInto(current)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                if (position == current) return
                captureInto(current)      // don't lose edits when switching away
                current = position
                loadInto(current)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val forgetButton = Button(this).apply {
            text = getString(R.string.settings_forget)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.settings_forget_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.settings_forget) { _, _ ->
                        val ok = controller.forgetAllMemories()
                        Toast.makeText(
                            this@MainActivity,
                            if (ok) R.string.toast_forgotten else R.string.toast_forget_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .show()
            }
        }

        val addButton = Button(this).apply {
            text = getString(R.string.settings_add_profile)
            setOnClickListener {
                captureInto(current)
                profiles.add(Prefs.Profile("档案 ${profiles.size + 1}", Prefs.DEFAULT_BASE_URL, "", "", ""))
                current = profiles.size - 1
                refreshSpinner(current)
                loadInto(current)
            }
        }

        layout.addView(spinner)
        layout.addView(nameInput)
        layout.addView(baseUrlInput)
        layout.addView(modelInput)
        layout.addView(keyInput)
        layout.addView(personaInput)
        layout.addView(idleInput)
        layout.addView(memoryToggle)
        layout.addView(addButton)
        layout.addView(forgetButton)
        layout.addView(TextView(this).apply {
            text = getString(R.string.settings_hint_gesture)
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(R.string.settings_save) { _, _ ->
                captureInto(current)
                prefs.profiles = profiles
                prefs.activeIndex = current
                prefs.memoryEnabled = memoryToggle.isChecked
                idleInput.text.toString().trim().toDoubleOrNull()
                    ?.let { prefs.idleMs = (it * 1000).toLong() }
                if (!prefs.configured) {
                    Toast.makeText(this, R.string.toast_need_key, Toast.LENGTH_LONG).show()
                }
            }
            .setOnDismissListener { controller.onResume() }
            .create()

        // The API key is on screen in this dialog. FLAG_SECURE keeps it out of screenshots,
        // screen recordings and the recents thumbnail.
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        dialog.show()
    }
}
