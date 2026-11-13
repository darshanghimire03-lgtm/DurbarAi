package com.durbar.ai.ui

import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.durbar.ai.DurbarAi
import com.durbar.ai.R
import com.durbar.ai.data.api.ApiException
import com.durbar.ai.data.api.ApiHelper
import com.durbar.ai.data.local.UserStore
import com.durbar.ai.data.model.AiModel
import com.durbar.ai.data.model.AiReply
import com.durbar.ai.data.model.ChatMessage
import com.durbar.ai.data.model.ChatSession
import com.durbar.ai.data.model.UserProfile
import com.durbar.ai.ui.adapter.ChatAdapter
import com.durbar.ai.ui.adapter.HistoryAdapter
import com.durbar.ai.ui.adapter.ModelAdapter
import com.durbar.ai.util.CalendarRepository
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var rvMessages: RecyclerView
    private lateinit var etQuestion: EditText
    private lateinit var ivSend: ImageView
    private lateinit var drawer: DrawerLayout
    private lateinit var emptyState: View
    private lateinit var tvGreeting: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvModelChip: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var inputBar: ConstraintLayout
    private lateinit var prefs: SharedPreferences
    private var profile = UserProfile()
    private val sessions = mutableListOf<ChatSession>()
    private var currentSession: ChatSession? = null
    private var isSending = false
    private var models: List<AiModel> = emptyList()
    private var selectedModelId: String? = null
    private var modelsLoading = false
    private var firstName = ""
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val collapsedSet = ConstraintSet()
    private val expandedSet = ConstraintSet()
    private var inputExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        drawer = findViewById(R.id.aiDrawer)
        val ivMenu = findViewById<ImageView>(R.id.ivAiMenu)
        val ivNewChat = findViewById<ImageView>(R.id.ivAiNewChat)
        val btnNewChat = findViewById<LinearLayout>(R.id.btnAiNewChat)
        val btnBack = findViewById<LinearLayout>(R.id.btnAiBack)
        val rvHistory = findViewById<RecyclerView>(R.id.rvAiHistory)
        emptyState = findViewById(R.id.aiEmptyState)
        tvGreeting = findViewById(R.id.tvAiGreeting)
        tvTitle = findViewById(R.id.tvAiTitle)
        tvModelChip = findViewById(R.id.tvAiModelChip)
        tvHistoryEmpty = findViewById(R.id.tvAiHistoryEmpty)
        ivSend = findViewById(R.id.ivAiSend)
        etQuestion = findViewById(R.id.etAiQuestion)
        rvMessages = findViewById(R.id.rvAiMessages)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedModelId = prefs.getString(KEY_MODEL, null)
        updateTitle()
        updateGreeting()
        loadModels(false)
        buildInputSets()
        watchKeyboard()

        adapter = ChatAdapter()
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateEmptyState()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
        })
        updateEmptyState()

        historyAdapter = HistoryAdapter(
            onOpen = { openSession(it) },
            onDelete = { deleteSession(it) }
        )
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
        refreshHistory()

        profile = UserStore.load(this)
        val extraName = intent.getStringExtra(DurbarAi.EXTRA_NAME).orEmpty()
        if (extraName.isNotBlank()) {
            profile = UserProfile(
                fullName = extraName,
                role = intent.getStringExtra(DurbarAi.EXTRA_ROLE).orEmpty().ifBlank { "student" },
                classLevel = intent.getStringExtra(DurbarAi.EXTRA_CLASS).orEmpty(),
                section = intent.getStringExtra(DurbarAi.EXTRA_SECTION).orEmpty()
            )
            UserStore.save(this, profile)
        }
        firstName = profile.firstName
        updateGreeting()
        preloadCalendar()
        tvGreeting.setOnClickListener { showProfileDialog(firstRun = false) }
        if (!UserStore.wasPrompted(this)) {
            showProfileDialog(firstRun = true)
        }

        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                drawer.closeDrawers()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                backCallback.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                backCallback.isEnabled = false
            }
        })

        ivMenu.setOnClickListener {
            refreshHistory()
            drawer.openDrawer(GravityCompat.START)
        }
        ivNewChat.setOnClickListener { startNewChat() }
        btnNewChat.setOnClickListener { startNewChat() }
        btnBack.setOnClickListener { finish() }
        ivSend.setOnClickListener { sendMessage() }

        tvTitle.setOnClickListener { openModelSelector() }
        tvModelChip.setOnClickListener { openModelSelector() }
    }

    override fun onDestroy() {
        keyboardListener?.let {
            window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        keyboardListener = null
        super.onDestroy()
    }

    private fun openModelSelector() {
        when {
            models.isNotEmpty() -> showModelPicker()
            modelsLoading -> Toast.makeText(this, "Loading models…", Toast.LENGTH_SHORT).show()
            else -> loadModels(true)
        }
    }

    private fun buildInputSets() {
        inputBar = findViewById(R.id.aiInputBar)
        collapsedSet.clone(inputBar)
        expandedSet.clone(inputBar)
        val input = R.id.etAiQuestion
        val send = R.id.ivAiSend
        val chip = R.id.tvAiModelChip
        val parent = ConstraintSet.PARENT_ID
        collapsedSet.setVisibility(chip, View.GONE)
        expandedSet.setVisibility(chip, View.VISIBLE)
        expandedSet.clear(input, ConstraintSet.END)
        expandedSet.clear(input, ConstraintSet.BOTTOM)
        expandedSet.connect(input, ConstraintSet.END, parent, ConstraintSet.END, dp(14))
        expandedSet.clear(send, ConstraintSet.TOP)
        expandedSet.clear(send, ConstraintSet.BOTTOM)
        expandedSet.connect(send, ConstraintSet.TOP, input, ConstraintSet.BOTTOM, 0)
        expandedSet.connect(send, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM, dp(8))

        inputBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height != oldBottom - oldTop) {
                rvMessages.setPadding(
                    rvMessages.paddingLeft,
                    rvMessages.paddingTop,
                    rvMessages.paddingRight,
                    height + dp(14) + dp(16)
                )
                if (::adapter.isInitialized && adapter.itemCount > 0) {
                    rvMessages.post { scrollToEnd() }
                }
            }
        }
    }

    private fun setInputExpanded(expanded: Boolean) {
        if (inputExpanded == expanded) return
        inputExpanded = expanded
        if (expanded) {
            expandedSet.applyTo(inputBar)
        } else {
            collapsedSet.applyTo(inputBar)
        }
    }

    private fun watchKeyboard() {
        val root = window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val frame = Rect()
            root.getWindowVisibleDisplayFrame(frame)
            val screenHeight = resources.displayMetrics.heightPixels
            val hidden = screenHeight - frame.bottom
            setInputExpanded(hidden > screenHeight * 0.15f)
        }
        keyboardListener = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        tvGreeting.text = if (firstName.isBlank()) greeting else "$greeting, $firstName"
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        if (adapter.itemCount == 0) updateGreeting()
    }

    private fun updateTitle() {
        val label = models.firstOrNull { it.id == selectedModelId }?.label ?: "Durbar Ai"
        tvTitle.text = "$label ▾"
        tvModelChip.text = "$label ▾"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun loadModels(openPickerWhenDone: Boolean) {
        if (modelsLoading) return
        modelsLoading = true
        lifecycleScope.launch {
            try {
                val result = ApiHelper.models()
                models = result.models
                val saved = prefs.getString(KEY_MODEL, null)
                selectedModelId = when {
                    models.any { it.id == saved } -> saved
                    models.any { it.id == result.defaultId } -> result.defaultId
                    else -> models.firstOrNull()?.id
                }
                saveSelectedModel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (openPickerWhenDone) {
                    Toast.makeText(
                        this@ChatActivity,
                        "Couldn't load models. Check your connection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                modelsLoading = false
                updateTitle()
            }
            if (openPickerWhenDone && models.isNotEmpty()) {
                showModelPicker()
            }
        }
    }

    private fun saveSelectedModel() {
        val id = selectedModelId ?: return
        prefs.edit().putString(KEY_MODEL, id).apply()
    }

    private fun showModelPicker() {
        if (models.isEmpty()) return
        val sheetHeight = (resources.displayMetrics.heightPixels * MODEL_SHEET_HEIGHT).toInt()
        val dialog = BottomSheetDialog(this, R.style.AiModelSheetDialogTheme)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_ai_models, null)
        content.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            sheetHeight
        )
        val list = content.findViewById<LinearLayout>(R.id.aiModelList)
        content.findViewById<View>(R.id.ivModelSheetClose).setOnClickListener {
            dialog.dismiss()
        }

        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.isNestedScrollingEnabled = false
        rv.adapter = ModelAdapter(models, selectedModelId) { model ->
            selectedModelId = model.id
            saveSelectedModel()
            updateTitle()
            dialog.dismiss()
        }
        list.addView(
            rv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(content)
        val behavior = dialog.behavior
        behavior.setShouldRemoveExpandedCorners(false)
        behavior.maxHeight = sheetHeight
        behavior.peekHeight = sheetHeight
        behavior.skipCollapsed = true
        behavior.isHideable = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun refreshHistory() {
        historyAdapter.submit(sessions.toList(), currentSession?.id)
        tvHistoryEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun persist() {
        val messages = adapter.snapshot()
        val firstUser = messages.firstOrNull { it.isUser } ?: return
        val session = currentSession ?: ChatSession(
            id = UUID.randomUUID().toString(),
            title = if (firstUser.text.length > 40) firstUser.text.take(40) + "…" else firstUser.text,
            updatedAt = System.currentTimeMillis(),
            messages = messages
        ).also {
            currentSession = it
            sessions.add(it)
        }
        session.messages = messages
        session.updatedAt = System.currentTimeMillis()
        sessions.sortByDescending { it.updatedAt }
        while (sessions.size > MAX_CHATS) {
            sessions.removeAt(sessions.size - 1)
        }
        refreshHistory()
    }

    private fun openSession(session: ChatSession) {
        if (isSending) {
            Toast.makeText(this, "Please wait for the reply", Toast.LENGTH_SHORT).show()
            return
        }
        currentSession = session
        adapter.submit(session.messages)
        updateEmptyState()
        scrollToEnd()
        refreshHistory()
        drawer.closeDrawers()
    }

    private fun deleteSession(session: ChatSession) {
        if (isSending && currentSession?.id == session.id) {
            Toast.makeText(this, "Please wait for the reply", Toast.LENGTH_SHORT).show()
            return
        }
        sessions.removeAll { it.id == session.id }
        if (currentSession?.id == session.id) {
            currentSession = null
            adapter.clear()
            updateEmptyState()
        }
        refreshHistory()
    }

    private fun startNewChat() {
        if (isSending) {
            Toast.makeText(this, "Please wait for the reply", Toast.LENGTH_SHORT).show()
            return
        }
        currentSession = null
        adapter.clear()
        updateEmptyState()
        refreshHistory()
        drawer.closeDrawers()
    }

    private fun setSending(sending: Boolean) {
        isSending = sending
        ivSend.isEnabled = !sending
        ivSend.alpha = if (sending) 0.5f else 1f
    }

    private fun scrollToEnd() {
        if (adapter.itemCount > 0) {
            rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun sendMessage() {
        val text = etQuestion.text.toString().trim()
        if (text.isEmpty() || isSending) return

        setSending(true)
        val history = adapter.history(12)
        val modelId = selectedModelId
        val userMessage = ChatMessage(text = text, isUser = true)
        adapter.add(userMessage)
        etQuestion.text?.clear()
        persist()
        adapter.add(ChatMessage(text = "Thinking…", isUser = false, isTyping = true, inHistory = false))
        scrollToEnd()

        lifecycleScope.launch {
            var errorText: String? = null
            val reply: AiReply? = try {
                ApiHelper.chat(text, history, profile.takeUnless { it.isEmpty }, modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 429) errorText = e.message
                null
            } catch (e: Exception) {
                null
            }

            adapter.removeTyping()
            if (reply != null) {
                val usedId = reply.modelId
                if (usedId != null && usedId != selectedModelId && models.any { it.id == usedId }) {
                    selectedModelId = usedId
                    saveSelectedModel()
                    updateTitle()
                }
                adapter.add(ChatMessage(text = reply.text, isUser = false, people = reply.people))
            } else {
                userMessage.inHistory = false
                adapter.add(
                    ChatMessage(
                        text = errorText ?: "Couldn't reach Durbar AI. Check your connection and try again.",
                        isUser = false,
                        inHistory = false
                    )
                )
            }
            persist()
            scrollToEnd()
            setSending(false)
        }
    }

    private fun preloadCalendar() {
        val adYear = Calendar.getInstance().get(Calendar.YEAR)
        listOf(adYear + 56, adYear + 57).forEach { year ->
            CalendarRepository.loadYear(year) { }
        }
    }

    private fun showProfileDialog(firstRun: Boolean) {
        val pad = dp(20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun field(hint: String, value: String, capitalize: Boolean): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                setSingleLine(true)
                inputType = if (capitalize) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            }
        }

        val etName = field("Your name", profile.fullName, true)
        val etClass = field("Class (e.g. 11)", profile.classLevel, false)
        val etSection = field("Section (e.g. A)", profile.section, false)
        form.addView(etName)
        form.addView(etClass)
        form.addView(etSection)

        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "Welcome to Durbar Ai" else "Your profile")
            .setMessage("Optional. Used to personalise answers.")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                profile = profile.copy(
                    fullName = etName.text.toString().trim(),
                    classLevel = etClass.text.toString().trim(),
                    section = etSection.text.toString().trim().uppercase()
                )
                UserStore.save(this, profile)
                firstName = profile.firstName
                updateGreeting()
            }
            .setNegativeButton(if (firstRun) "Skip" else "Cancel") { _, _ ->
                if (firstRun) UserStore.markPrompted(this)
            }
            .setOnCancelListener {
                if (firstRun) UserStore.markPrompted(this)
            }
            .show()
    }

    companion object {
        private const val MAX_CHATS = 50
        private const val PREFS_NAME = "durbar_ai_prefs"
        private const val KEY_MODEL = "selected_model"
        private const val MODEL_SHEET_HEIGHT = 0.5f
    }
}
