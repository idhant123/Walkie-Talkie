package com.example.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class NearbyManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val endpointId = UUID.randomUUID().toString().substring(0, 6)
    
    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints = _connectedEndpoints.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering = _isDiscovering.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private fun log(message: String) {
        _logs.value = _logs.value.takeLast(9) + message
    }

    var onAudioReceived: ((ByteArray) -> Unit)? = null

    // P2P_CLUSTER supports an M-to-N network topology, ideal for walkie-talkies.
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.example.walkie_talkie"

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpoint: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    onAudioReceived?.invoke(bytes)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpoint: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) {
            log("Connection initiated with $endpoint")
            connectionsClient.acceptConnection(endpoint, payloadCallback)
                .addOnFailureListener { e -> log("Accept failed: ${e.message}") }
        }

        override fun onConnectionResult(endpoint: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                log("Connected to $endpoint")
                _connectedEndpoints.value = _connectedEndpoints.value + endpoint
            } else {
                log("Connection failed: ${resolution.status.statusCode}")
            }
        }

        override fun onDisconnected(endpoint: String) {
            log("Disconnected from $endpoint")
            _connectedEndpoints.value = _connectedEndpoints.value - endpoint
        }
    }

    fun startAdvertising(channelCode: String) {
        val serviceId = "com.example.walkie_talkie.$channelCode"
        log("Starting advertising on $channelCode...")
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            endpointId,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            log("Advertising started")
            _isAdvertising.value = true
        }.addOnFailureListener { e ->
            log("Advertising failed: ${e.message}")
            _isAdvertising.value = false
        }
    }

    fun startDiscovery(channelCode: String) {
        val serviceId = "com.example.walkie_talkie.$channelCode"
        log("Starting discovery on $channelCode...")
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpoint: String, info: DiscoveredEndpointInfo) {
                    log("Found endpoint: $endpoint, requesting connection...")
                    connectionsClient.requestConnection(endpointId, endpoint, connectionLifecycleCallback)
                        .addOnFailureListener { e -> log("Request failed: ${e.message}") }
                }
                override fun onEndpointLost(endpoint: String) {
                    log("Lost endpoint: $endpoint")
                }
            },
            options
        ).addOnSuccessListener {
            log("Discovery started")
            _isDiscovering.value = true
        }.addOnFailureListener { e ->
            log("Discovery failed: ${e.message}")
            _isDiscovering.value = false
        }
    }

    fun sendAudio(data: ByteArray) {
        val endpoints = _connectedEndpoints.value.toList()
        if (endpoints.isNotEmpty()) {
            connectionsClient.sendPayload(endpoints, Payload.fromBytes(data))
        }
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _isAdvertising.value = false
        _isDiscovering.value = false
        _connectedEndpoints.value = emptySet()
    }
}
