package com.example

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioHandler
import com.example.nearby.NearbyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WalkieTalkieViewModel(private val context: Context) : ViewModel() {
    private val nearbyManager = NearbyManager(context)
    private val audioHandler = AudioHandler()

    val connectedEndpoints = nearbyManager.connectedEndpoints
    val isAdvertising = nearbyManager.isAdvertising
    val isDiscovering = nearbyManager.isDiscovering
    val logs = nearbyManager.logs

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    private val _isReceivingData = MutableStateFlow(false)
    val isReceivingData = _isReceivingData.asStateFlow()
    
    private val _channelCode = MutableStateFlow(generateRandomCode())
    val channelCode = _channelCode.asStateFlow()

    private fun generateRandomCode(): String {
        return kotlin.random.Random.nextInt(1000, 10000).toString()
    }
    
    private var lastReceiveTime = 0L

    fun generateNewCode() {
        _channelCode.value = generateRandomCode()
    }

    fun setChannelCode(code: String) {
        if (code.length <= 4 && code.all { it.isDigit() }) {
            _channelCode.value = code
        }
    }

    init {
        nearbyManager.onAudioReceived = { data ->
            audioHandler.playAudio(data)
            
            // Simple visualizer pulse
            lastReceiveTime = System.currentTimeMillis()
            _isReceivingData.value = true
        }

        viewModelScope.launch {
            audioHandler.audioFlow.collect { data ->
                nearbyManager.sendAudio(data)
            }
        }

        // Loop to clear receive state
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                if (System.currentTimeMillis() - lastReceiveTime > 500) {
                    _isReceivingData.value = false
                }
            }
        }
    }

    fun toggleConnection(enabled: Boolean) {
        if (enabled) {
            val code = _channelCode.value.takeIf { it.isNotEmpty() } ?: "0000"
            nearbyManager.startAdvertising(code)
            nearbyManager.startDiscovery(code)
            audioHandler.startPlaying()
        } else {
            nearbyManager.stopAll()
            audioHandler.stopPlaying()
        }
    }

    fun startTransmitting() {
        if (connectedEndpoints.value.isNotEmpty()) {
            _isTransmitting.value = true
            audioHandler.startRecording()
        }
    }

    fun stopTransmitting() {
        _isTransmitting.value = false
        audioHandler.stopRecording()
    }

    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopAll()
        audioHandler.stopRecording()
        audioHandler.stopPlaying()
    }
}

class WalkieTalkieViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WalkieTalkieViewModel(context) as T
    }
}
