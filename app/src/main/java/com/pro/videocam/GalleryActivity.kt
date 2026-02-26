package com.pro.videocam

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pro.videocam.databinding.ActivityGalleryBinding
import java.io.File
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val executor = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        loadVideos()
    }

    private fun loadVideos() {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
            "ProCam"
        )
        val videos = dir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (videos.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvGallery.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvGallery.visibility = View.VISIBLE
            binding.rvGallery.layoutManager = GridLayoutManager(this, 2)
            binding.rvGallery.adapter = VideoAdapter(videos.toMutableList())
        }
    }

    inner class VideoAdapter(private val videos: MutableList<File>) :
        RecyclerView.Adapter<VideoAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.ivThumb)
            val duration: TextView = view.findViewById(R.id.tvDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false))

        override fun getItemCount() = videos.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = videos[position]
            holder.duration.text = getDuration(file)
            executor.execute {
                val bmp = ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                holder.thumb.post { bmp?.let { holder.thumb.setImageBitmap(it) } }
            }
            holder.itemView.setOnClickListener { playVideo(file) }
            holder.itemView.setOnLongClickListener { showDeleteDialog(file, position); true }
        }

        private fun getDuration(file: File): String = try {
            val r = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            r.release()
            val s = ms / 1000
            "%02d:%02d".format(s / 60, s % 60)
        } catch (_: Exception) { "--:--" }

        private fun showDeleteDialog(file: File, position: Int) {
            AlertDialog.Builder(this@GalleryActivity)
                .setTitle("Delete Video")
                .setMessage("Delete this video permanently?")
                .setPositiveButton("Delete") { _, _ ->
                    file.delete()
                    videos.removeAt(position)
                    notifyItemRemoved(position)
                    if (videos.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvGallery.visibility = View.GONE
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun playVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "No video player installed", Toast.LENGTH_SHORT).show() }
    }
}
