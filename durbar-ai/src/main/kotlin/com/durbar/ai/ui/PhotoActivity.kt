package com.durbar.ai.ui

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.durbar.ai.R
import com.github.chrisbanes.photoview.PhotoView

class PhotoActivity : Activity() {

    private lateinit var photoPager: ViewPager2
    private lateinit var topBar: View
    private lateinit var backButton: ImageButton
    private lateinit var downloadButton: ImageButton
    private lateinit var shareButton: ImageButton

    private var imageUrls: List<String> = emptyList()
    private var isTopBarVisible = true
    private var pendingDownloadUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_photo)

        photoPager = findViewById(R.id.photoPager)
        topBar = findViewById(R.id.topBar)
        backButton = findViewById(R.id.backButton)
        downloadButton = findViewById(R.id.downloadButton)
        shareButton = findViewById(R.id.shareButton)

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                bars.left + dp(8),
                bars.top + dp(8),
                bars.right + dp(8),
                dp(8)
            )
            insets
        }

        imageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS).orEmpty()

        if (imageUrls.isEmpty()) {
            finish()
            return
        }

        val startPosition = intent
            .getIntExtra(EXTRA_START_POSITION, 0)
            .coerceIn(0, imageUrls.lastIndex)

        photoPager.adapter = PhotoAdapter(imageUrls) { toggleTopBar() }
        photoPager.setCurrentItem(startPosition, false)

        backButton.setOnClickListener { finish() }
        downloadButton.setOnClickListener { downloadCurrentPhoto() }
        shareButton.setOnClickListener { shareCurrentPhoto() }
    }

    private fun toggleTopBar() {
        if (isTopBarVisible) {
            isTopBarVisible = false
            topBar.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction {
                    if (!isTopBarVisible) topBar.visibility = View.GONE
                }
                .start()
        } else {
            isTopBarVisible = true
            topBar.visibility = View.VISIBLE
            topBar.animate()
                .alpha(1f)
                .setDuration(180)
                .start()
        }
    }

    private fun shareCurrentPhoto() {
        val url = imageUrls.getOrNull(photoPager.currentItem) ?: return

        val shareLink = Uri.Builder()
            .scheme("https")
            .authority("durbar-plus2.vercel.app")
            .appendPath("durbar")
            .appendPath("img")
            .appendPath(imageName(url))
            .appendQueryParameter("u", url)
            .build()
            .toString()

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareLink)
        }
        startActivity(Intent.createChooser(sendIntent, "Share photo via"))
    }

    private fun downloadCurrentPhoto() {
        val url = imageUrls.getOrNull(photoPager.currentItem) ?: return

        if (!url.startsWith("http")) {
            Toast.makeText(this, "Photo is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadUrl = url
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
            return
        }

        enqueueDownload(url)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            val url = pendingDownloadUrl
            pendingDownloadUrl = null
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                url != null
            ) {
                enqueueDownload(url)
            } else {
                Toast.makeText(this, "Storage permission is required to download", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueDownload(url: String) {
        try {
            val fileName = downloadFileName(url)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_PICTURES,
                    "Durbar/$fileName"
                )

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to download photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun imageName(url: String): String {
        val raw = Uri.parse(url).lastPathSegment.orEmpty()
        val clean = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return clean.ifBlank { "image" }
    }

    private fun downloadFileName(url: String): String {
        val name = imageName(url)
        return if (name.contains('.')) name else "$name.jpg"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_IMAGE_URLS = "image_urls"
        const val EXTRA_START_POSITION = "start_position"

        private const val REQUEST_STORAGE_PERMISSION = 1001

        fun start(
            context: Context,
            imageUrls: List<String>,
            startPosition: Int = 0
        ) {
            if (imageUrls.isEmpty()) return
            val intent = Intent(context, PhotoActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_IMAGE_URLS, ArrayList(imageUrls))
                putExtra(EXTRA_START_POSITION, startPosition)
            }
            if (!isActivityContext(context)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private fun isActivityContext(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return true
                current = current.baseContext
            }
            return false
        }
    }
}

private class PhotoAdapter(
    private val imageUrls: List<String>,
    private val onPhotoTap: () -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    class PhotoViewHolder(
        val photoView: PhotoView
    ) : RecyclerView.ViewHolder(photoView)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): PhotoViewHolder {
        val photoView = PhotoView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            maximumScale = 4f

            setOnViewTapListener { _, _, _ -> onPhotoTap() }
        }
        return PhotoViewHolder(photoView)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        Glide.with(holder.photoView)
            .load(imageUrls[position])
            .fitCenter()
            .error(android.R.drawable.ic_menu_report_image)
            .into(holder.photoView)
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        Glide.with(holder.photoView).clear(holder.photoView)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = imageUrls.size
}