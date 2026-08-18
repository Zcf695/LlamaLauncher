package com.llama.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.llama.launcher.databinding.ItemChatMessageBinding

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    data class Message(val role: String, val content: String)

    private val messages = mutableListOf<Message>()

    fun addMessage(role: String, content: String) {
        messages.add(Message(role, content))
        notifyItemInserted(messages.size - 1)
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    class ViewHolder(private val binding: ItemChatMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            if (message.role == "user") {
                binding.userMessageLayout.visibility = View.VISIBLE
                binding.aiMessageLayout.visibility = View.GONE
                binding.userMessageText.text = message.content
            } else {
                binding.userMessageLayout.visibility = View.GONE
                binding.aiMessageLayout.visibility = View.VISIBLE
                binding.aiMessageText.text = message.content
            }
        }
    }
}
