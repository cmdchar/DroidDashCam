package com.helge.droiddashcam.ui

import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.helge.droiddashcam.R
import com.helge.droiddashcam.databinding.FragmentGalleryBinding
import com.helge.droiddashcam.databinding.ItemVideoBinding
import java.text.SimpleDateFormat
import java.util.*

class GalleryFragment : Fragment() {
    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        loadVideos()
    }

    private fun loadVideos() {
        val videoList = mutableListOf<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Movies/DroidDashCam%")

        requireContext().contentResolver.query(
            collection,
            projection,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selection else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selectionArgs else null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val date = cursor.getLong(dateColumn)
                val size = cursor.getLong(sizeColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                videoList.add(VideoItem(contentUri.toString(), name, date * 1000, size))
            }
        }

        if (videoList.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
        } else {
            binding.recyclerView.adapter = VideoAdapter(videoList, { video ->
                val action = GalleryFragmentDirections.actionGalleryToReview(video.uri)
                findNavController().navigate(action)
            }, { video ->
                openExternalPlayer(video.uri)
            })
        }
    }

    private fun openExternalPlayer(uriString: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(uriString), "video/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Open with..."))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "No app to open video", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    data class VideoItem(val uri: String, val name: String, val timestamp: Long, val size: Long)

    inner class VideoAdapter(
        private val videos: List<VideoItem>,
        private val onClick: (VideoItem) -> Unit,
        private val onLongClick: (VideoItem) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemVideoBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val video = videos[position]
            holder.itemBinding.textName.text = video.name
            val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(video.timestamp))
            holder.itemBinding.textInfo.text = "$date • ${video.size / 1024 / 1024} MB"

            Glide.with(this@GalleryFragment)
                .load(video.uri)
                .centerCrop()
                .into(holder.itemBinding.imgThumbnail)

            holder.itemView.setOnClickListener { onClick(video) }
            holder.itemView.setOnLongClickListener {
                onLongClick(video)
                true
            }
        }

        override fun getItemCount() = videos.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
