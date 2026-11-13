package com.durbar.ai.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.durbar.ai.DurbarAi
import com.durbar.ai.R
import com.durbar.ai.data.api.ApiException
import com.durbar.ai.data.api.ApiHelper
import com.durbar.ai.data.api.ImgbbUploader
import com.durbar.ai.data.local.ChatStore
import com.durbar.ai.data.local.UserStore
import com.durbar.ai.data.model.AiModel
import com.durbar.ai.data.model.ChatMessage
import com.durbar.ai.data.model.ChatSession
import com.durbar.ai.data.model.StreamEvent
import com.durbar.ai.data.model.UserProfile
import com.durbar.ai.ui.adapter.ChatAdapter
import com.durbar.ai.ui.adapter.HistoryAdapter
import com.durbar.ai.ui.adapter.ModelAdapter
import com.durbar.ai.util.CalendarRepository
import com.durbar.ai.util.Constants
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var rvMessages: RecyclerView
    private lateinit var etQuestion: EditText
    private lateinit var ivSend: ImageView
    private lateinit var ivMic: ImageView
    private lateinit var ivPlus: ImageView
    private lateinit var imageScroll: HorizontalScrollView
    private lateinit var imageStrip: LinearLayout
    private lateinit var drawer: DrawerLayout
    private lateinit var emptyState: View
    private lateinit var tvGreeting: TextView
    private lateinit var tvModelChip: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var inputBar: ConstraintLayout
    private lateinit var prefs: SharedPreferences
    private var profile = UserProfile()
    private val sessions = mutableListOf<ChatSession>()
    private val pendingImages = mutableListOf<Uri>()
    private var currentSession: ChatSession? = null
    private var isSending = false
    private var sendJob: Job? = null
    private var models: List<AiModel> = emptyList()
    private var selectedModelId: String? = null
    private var modelsLoading = false
    private var firstName = ""
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val collapsedSet = ConstraintSet()
    private val expandedSet = ConstraintSet()
    private var inputExpanded = false
    private val streamBuffer = StringBuilder()
    private var streamStatus = THINKING
    private var lastPush = 0L
    private var flushScheduled = false
    private var stickToBottom = true
    private val flushRunnable = Runnable {
        flushScheduled = false
        pushStream()
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                val current = etQuestion.text.toString()
                val joined = if (current.isBlank()) spoken else current.trimEnd() + " " + spoken
                etQuestion.setText(joined)
                etQuestion.setSelection(joined.length)
            }
        }
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        addPendingImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyStyle(R.style.AiSelectionOverlay, true)
        setContentView(R.layout.activity_chat)

        drawer = findViewById(R.id.aiDrawer)
        val ivMenu = findViewById<ImageView>(R.id.ivAiMenu)
        val ivNewChat = findViewById<ImageView>(R.id.ivAiNewChat)
        val btnNewChat = findViewById<LinearLayout>(R.id.btnAiNewChat)
        val rvHistory = findViewById<RecyclerView>(R.id.rvAiHistory)
        emptyState = findViewById(R.id.aiEmptyState)
        tvGreeting = findViewById(R.id.tvAiGreeting)
        tvModelChip = findViewById(R.id.tvAiModelChip)
        tvHistoryEmpty = findViewById(R.id.tvAiHistoryEmpty)
        ivSend = findViewById(R.id.ivAiSend)
        ivMic = findViewById(R.id.ivAiMic)
        ivPlus = findViewById(R.id.ivAiPlus)
        etQuestion = findViewById(R.id.etAiQuestion)
        rvMessages = findViewById(R.id.rvAiMessages)
        imageScroll = findViewById(R.id.aiImageScroll)
        imageStrip = findViewById(R.id.aiImageStrip)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedModelId = prefs.getString(KEY_MODEL, null)
        sessions.addAll(ChatStore.load(this))
        while (sessions.size > MAX_CHATS) {
            sessions.removeAt(sessions.size - 1)
        }
        updateTitle()
        updateGreeting()
        loadModels(false)
        buildInputSets()
        watchKeyboard()

        adapter = ChatAdapter()
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter
        (rvMessages.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    stickToBottom = false
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(1)) {
                    stickToBottom = true
                }
            }
        })
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

        ivPlus.setOnClickListener { openImagePicker() }
        ivMic.setOnClickListener { startVoice() }
        ivSend.setOnClickListener {
            when {
                isSending -> stopGeneration()
                etQuestion.text.isNullOrBlank() && pendingImages.isEmpty() -> if (!inputExpanded) startVoice()
                else -> sendMessage()
            }
        }
        etQuestion.doAfterTextChanged { updateActionButtons() }
        tvModelChip.setOnClickListener { openModelSelector() }
        updateActionButtons()
    }

    override fun onDestroy() {
        keyboardListener?.let {
            window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        keyboardListener = null
        rvMessages.removeCallbacks(flushRunnable)
        super.onDestroy()
    }

    private fun openImagePicker() {
        if (pendingImages.size >= Constants.MAX_INPUT_IMAGES) {
            Toast.makeText(this, "You can add up to ${Constants.MAX_INPUT_IMAGES} photos", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            imagePicker.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addPendingImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        var added = 0
        for (uri in uris) {
            if (pendingImages.size >= Constants.MAX_INPUT_IMAGES) break
            if (pendingImages.contains(uri)) continue
            pendingImages.add(uri)
            added++
        }
        if (added < uris.size) {
            Toast.makeText(this, "Up to ${Constants.MAX_INPUT_IMAGES} photos per message", Toast.LENGTH_SHORT).show()
        }
        renderPendingImages()
    }

    private fun removePendingImage(uri: Uri) {
        pendingImages.remove(uri)
        renderPendingImages()
    }

    private fun renderPendingImages() {
        imageStrip.removeAllViews()
        pendingImages.forEach { uri ->
            val item = layoutInflater.inflate(R.layout.item_image_input, imageStrip, false)
            val preview = item.findViewById<ImageView>(R.id.ivInputImage)
            Glide.with(this).load(uri).centerCrop().into(preview)
            item.findViewById<View>(R.id.ivInputRemove).setOnClickListener { removePendingImage(uri) }
            imageStrip.addView(item)
        }
        imageScroll.visibility = if (pendingImages.isEmpty()) View.GONE else View.VISIBLE
        updateActionButtons()
        updateListPadding()
    }

    private fun updateListPadding() {
        if (!::inputBar.isInitialized || !::rvMessages.isInitialized) return
        val strip = if (imageScroll.visibility == View.VISIBLE) imageScroll.height + dp(6) else 0
        rvMessages.setPadding(
            rvMessages.paddingLeft,
            rvMessages.paddingTop,
            rvMessages.paddingRight,
            inputBar.height + strip + dp(14) + dp(16)
        )
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            rvMessages.post { scrollToEnd() }
        }
    }

    private fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGeneration() {
        sendJob?.cancel()
    }

    private fun updateActionButtons() {
        if (!::ivSend.isInitialized || !::ivMic.isInitialized) return
        val empty = etQuestion.text.isNullOrBlank() && pendingImages.isEmpty()
        when {
            isSending -> {
                ivSend.setImageResource(R.drawable.ic_ai_stop)
                ivSend.contentDescription = "Stop"
                ivSend.alpha = 1f
                ivMic.visibility = View.GONE
            }
            inputExpanded -> {
                ivMic.visibility = View.VISIBLE
                ivSend.setImageResource(R.drawable.ic_ai_send_up)
                ivSend.contentDescription = "Send"
                ivSend.alpha = if (empty) 0.4f else 1f
            }
            else -> {
                ivMic.visibility = View.GONE
                ivSend.setImageResource(if (empty) R.drawable.ic_ai_mic else R.drawable.ic_ai_send_up)
                ivSend.contentDescription = if (empty) "Voice input" else "Send"
                ivSend.alpha = 1f
            }
        }
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
        val mic = R.id.ivAiMic
        val parent = ConstraintSet.PARENT_ID
        collapsedSet.setVisibility(chip, View.GONE)
        collapsedSet.setVisibility(mic, View.GONE)
        expandedSet.setVisibility(chip, View.VISIBLE)
        expandedSet.setVisibility(mic, View.VISIBLE)
        expandedSet.clear(input, ConstraintSet.START)
        expandedSet.clear(input, ConstraintSet.END)
        expandedSet.clear(input, ConstraintSet.BOTTOM)
        expandedSet.connect(input, ConstraintSet.START, parent, ConstraintSet.START, dp(14))
        expandedSet.connect(input, ConstraintSet.END, parent, ConstraintSet.END, dp(14))
        expandedSet.clear(send, ConstraintSet.TOP)
        expandedSet.clear(send, ConstraintSet.BOTTOM)
        expandedSet.connect(send, ConstraintSet.TOP, input, ConstraintSet.BOTTOM, 0)
        expandedSet.connect(send, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM, dp(8))

        inputBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updateListPadding()
        }
        imageScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updateListPadding()
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
        updateActionButtons()
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
        val label = firstUser.text.ifBlank { "Photo" }
        val session = currentSession ?: ChatSession(
            id = UUID.randomUUID().toString(),
            title = if (label.length > 40) label.take(40) + "…" else label,
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
        ChatStore.save(this, sessions)
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
        ChatStore.save(this, sessions)
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
        updateActionButtons()
    }

    private fun scrollToEnd() {
        val last = adapter.itemCount - 1
        if (last < 0) return
        rvMessages.scrollToPosition(last)
        rvMessages.post { alignBottom() }
    }

    private fun alignBottom() {
        val last = adapter.itemCount - 1
        if (last < 0) return
        val manager = rvMessages.layoutManager as LinearLayoutManager
        val view = manager.findViewByPosition(last)
        if (view == null) {
            rvMessages.scrollToPosition(last)
            return
        }
        val overflow = view.bottom - (rvMessages.height - rvMessages.paddingBottom)
        if (overflow > 0) rvMessages.scrollBy(0, overflow)
    }

    private fun scheduleStreamPush() {
        val wait = PUSH_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPush)
        if (wait <= 0) {
            rvMessages.removeCallbacks(flushRunnable)
            flushScheduled = false
            pushStream()
        } else if (!flushScheduled) {
            flushScheduled = true
            rvMessages.postDelayed(flushRunnable, wait)
        }
    }

    private fun pushStream() {
        lastPush = SystemClock.uptimeMillis()
        adapter.updateLast(streamBuffer.toString(), streamStatus)
        if (stickToBottom) rvMessages.post { alignBottom() }
    }

    private fun cancelPendingPush() {
        rvMessages.removeCallbacks(flushRunnable)
        flushScheduled = false
    }

    private fun adoptModel(usedId: String?) {
        if (usedId != null && usedId != selectedModelId && models.any { it.id == usedId }) {
            selectedModelId = usedId
            saveSelectedModel()
            updateTitle()
        }
    }

    private fun sendMessage() {
        val text = etQuestion.text.toString().trim()
        val picked = pendingImages.toList()
        if ((text.isEmpty() && picked.isEmpty()) || isSending) return

        setSending(true)
        val history = adapter.history(12)
        val modelId = selectedModelId
        val userMessage = ChatMessage(
            text = text,
            isUser = true,
            images = picked.map { it.toString() }
        )
        adapter.add(userMessage)
        etQuestion.text?.clear()
        pendingImages.clear()
        renderPendingImages()
        persist()
        val firstStatus = if (picked.isEmpty()) THINKING else UPLOADING
        adapter.add(
            ChatMessage(
                text = "",
                isUser = false,
                isTyping = true,
                inHistory = false,
                status = firstStatus
            )
        )
        streamBuffer.setLength(0)
        streamStatus = firstStatus
        lastPush = 0L
        stickToBottom = true
        scrollToEnd()

        sendJob = lifecycleScope.launch {
            var finished = false
            var stopped = false
            var failure: String? = null
            var remote: List<String> = emptyList()

            try {
                if (picked.isNotEmpty()) {
                    var uploadReason: String? = null
                    val uploaded = try {
                        coroutineScope {
                            picked.map { uri ->
                                async { ImgbbUploader.upload(applicationContext, uri) }
                            }.awaitAll()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        uploadReason = e.message
                        null
                    }
                    if (uploaded == null) {
                        failure = if (uploadReason.isNullOrBlank()) UPLOAD_ERROR
                        else "$UPLOAD_ERROR\n\n($uploadReason)"
                    } else {
                        remote = uploaded
                        userMessage.images = uploaded
                        adapter.refresh(userMessage)
                        streamStatus = THINKING
                        pushStream()
                    }
                }

                if (failure == null) {
                    ApiHelper.chatStream(text, history, profile.takeUnless { it.isEmpty }, modelId, remote)
                        .collect { event ->
                            when (event) {
                                is StreamEvent.Start -> adoptModel(event.modelId)
                                is StreamEvent.Delta -> {
                                    streamBuffer.append(event.text)
                                    scheduleStreamPush()
                                }
                                is StreamEvent.Status -> {
                                    streamStatus = event.text.ifBlank { WORKING }
                                    pushStream()
                                }
                                is StreamEvent.Reset -> {
                                    streamBuffer.setLength(0)
                                    pushStream()
                                }
                                is StreamEvent.Done -> {
                                    adoptModel(event.modelId)
                                    val finalText = event.text.ifBlank { streamBuffer.toString().trim() }
                                    if (finalText.isNotBlank() || event.people.isNotEmpty() || event.images.isNotEmpty()) {
                                        cancelPendingPush()
                                        finished = true
                                        adapter.finishLast(finalText, event.people, true, event.images)
                                    }
                                }
                                is StreamEvent.Failure -> failure = event.message
                            }
                        }
                }
            } catch (e: CancellationException) {
                stopped = true
            } catch (e: ApiException) {
                if (e.code == 429) failure = e.message
            } catch (e: Exception) {
                failure = null
            }

            cancelPendingPush()
            if (!finished) {
                val partial = streamBuffer.toString().trim()
                if (stopped) {
                    if (partial.isEmpty()) {
                        userMessage.inHistory = false
                        adapter.removeTyping()
                    } else {
                        adapter.finishLast(partial, emptyList(), true)
                    }
                } else {
                    userMessage.inHistory = false
                    val note = failure ?: GENERIC_ERROR
                    val body = if (partial.isEmpty()) note else "$partial\n\n_${note}_"
                    adapter.finishLast(body, emptyList(), false)
                }
            }
            persist()
            if (stickToBottom) scrollToEnd()
            sendJob = null
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
        private const val PUSH_INTERVAL_MS = 40L
        private const val THINKING = "Thinking…"
        private const val UPLOADING = "Uploading photos…"
        private const val WORKING = "Working on it…"
        private const val GENERIC_ERROR = "Couldn't reach Durbar AI. Check your connection and try again."
        private const val UPLOAD_ERROR = "Couldn't upload the photo. Check your connection and try again."
    }
}