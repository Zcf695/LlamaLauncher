package com.llama.launcher

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.llama.launcher.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var settings: SettingsManager
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val conversationHistory = mutableListOf<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        adapter = ChatAdapter()

        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.chatRecyclerView.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.clearChatButton.setOnClickListener {
            adapter.clear()
            conversationHistory.clear()
        }
        binding.sendButton.setOnClickListener { sendMessage() }
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty()) return

        binding.messageInput.setText("")
        adapter.addMessage("user", text)
        scrollToBottom()

        // 添加到历史
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        // 显示"正在思考"
        val thinkingPos = adapter.itemCount
        adapter.addMessage("assistant", "正在思考...")
        scrollToBottom()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = callLLM()
                withContext(Dispatchers.Main) {
                    // 替换"正在思考"
                    adapter.notifyItemChanged(thinkingPos)
                    // 直接更新最后一条消息
                    val lastMsg = (adapter as? ChatAdapter)?.let {
                        // 简单方式：添加新消息
                    }
                    adapter.addMessage("assistant", response)
                    scrollToBottom()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    adapter.addMessage("assistant", "错误: ${e.message}")
                    scrollToBottom()
                }
            }
        }
    }

    private fun callLLM(): String {
        val port = settings.port
        val url = "http://127.0.0.1:$port/v1/chat/completions"

        val messages = JSONArray()
        // 系统提示
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", "你是一个有帮助的 AI 助手。")
        })
        // 历史消息
        for (msg in conversationHistory) {
            messages.put(msg)
        }

        val body = JSONObject().apply {
            put("model", "local")
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", settings.maxTokens)
            put("stream", false)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val json = JSONObject(response.body?.string() ?: "")
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                return choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            return "(空响应)"
        }
    }

    private fun scrollToBottom() {
        binding.chatRecyclerView.post {
            binding.chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
        }
    }
}
