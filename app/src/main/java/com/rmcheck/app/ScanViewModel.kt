package com.rmcheck.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class PingState { Pending, Replying, NoReply, NotAvailable }

enum class StatusKind { Info, Ok, Warn, Error }

data class HostResult(
    val ip: String,
    val ping: PingState,
    val pingMs: Double?,
    val ports: List<PortResult>
)

class ScanViewModel : ViewModel() {

    // ---- state shown on screen -------------------------------------------
    var ipText by mutableStateOf("")
    var portsText by mutableStateOf(DEFAULT_PORTS.joinToString(","))

    var scanning by mutableStateOf(false)
        private set
    var stopping by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var hosts by mutableStateOf<List<HostResult>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var openPorts by mutableStateOf(0)
        private set
    var respondingHosts by mutableStateOf(0)
        private set
    var progress by mutableStateOf(0f)
        private set
    var pingState by mutableStateOf(PingState.Pending)   // used when a single IP is scanned
        private set
    var pingMs by mutableStateOf<Double?>(null)
        private set
    var statusText by mutableStateOf("Ready. Enter an IP address or range and press Scan.")
        private set
    var statusKind by mutableStateOf(StatusKind.Info)
        private set

    // ---- scan internals ---------------------------------------------------
    private val portResults = ConcurrentHashMap<String, CopyOnWriteArrayList<PortResult>>()
    private val pingResults = ConcurrentHashMap<String, PingResult>()
    private val scanDone = AtomicInteger(0)
    private val pingDone = AtomicInteger(0)
    private var targets: List<String> = emptyList()
    private var job: Job? = null

    private val ioDispatcher = Executors.newFixedThreadPool(POOL_THREADS) { r ->
        Thread(r).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    fun setStatus(text: String, kind: StatusKind) {
        statusText = text
        statusKind = kind
    }

    fun resetPorts() {
        portsText = DEFAULT_PORTS.joinToString(",")
    }

    fun toggle() {
        if (scanning) stop() else start()
    }

    fun stop() {
        if (!scanning || stopping) return
        stopping = true
        setStatus("Stopping the scan...", StatusKind.Warn)
        job?.cancel()
    }

    fun start() {
        if (scanning) return

        val parsed = parseTargets(ipText)
        val error = parsed.error
        if (error != null) {
            setStatus(error, if (error.startsWith("Please")) StatusKind.Warn else StatusKind.Error)
            return
        }
        val ports = parsePorts(portsText)
        if (ports == null) {
            setStatus("Invalid port list. Use numbers 1-65535 separated by commas, e.g. 80,443,8888", StatusKind.Error)
            return
        }

        targets = parsed.hosts
        portResults.clear()
        pingResults.clear()
        scanDone.set(0)
        pingDone.set(0)
        total = targets.size
        hosts = emptyList()
        openPorts = 0
        respondingHosts = 0
        progress = 0f
        pingState = PingState.Pending
        pingMs = null
        finished = false
        stopping = false
        scanning = true
        setStatus("Scanning ${targets.size} host(s) x ${ports.size} ports...", StatusKind.Info)

        val pingCount = if (targets.size == 1) 3 else 2
        val hostList = targets
        job = viewModelScope.launch(Dispatchers.Default) {
            val publisher = launch {
                while (isActive) {
                    publish()
                    delay(150)
                }
            }
            try {
                runScan(hostList, ports, pingCount)
            } finally {
                publisher.cancel()
                withContext(NonCancellable) {
                    publish()
                    finishScan()
                }
            }
        }
    }

    private suspend fun runScan(hostList: List<String>, ports: List<Int>, pingCount: Int) {
        coroutineScope {
            val pingSem = Semaphore(PING_CONCURRENCY)
            val probeSem = Semaphore(PROBE_CONCURRENCY)
            for (h in hostList) {
                launch(ioDispatcher) {
                    pingSem.withPermit {
                        ensureActive()
                        pingResults[h] = Scanner.ping(h, pingCount)
                    }
                    pingDone.incrementAndGet()
                }
            }
            for (h in hostList) {
                launch {
                    scanHost(h, ports, probeSem)
                    scanDone.incrementAndGet()
                }
            }
        }
    }

    private suspend fun scanHost(h: String, ports: List<Int>, probeSem: Semaphore) {
        coroutineScope {
            for (p in ports) {
                launch(ioDispatcher) {
                    probeSem.withPermit {
                        ensureActive()
                        val r = Scanner.probe(h, p)
                        if (r != null) {
                            portResults.getOrPut(h) { CopyOnWriteArrayList() }.add(r)
                        }
                    }
                }
            }
        }
    }

    /** Builds the list shown on screen from the results collected so far (runs every 150 ms). */
    private fun publish() {
        val list = ArrayList<HostResult>()
        var open = 0
        var responding = 0
        for (h in targets) {
            val ports = portResults[h]?.toList().orEmpty().sortedBy { it.port }
            val pr = pingResults[h]
            val state = when {
                pr == null -> PingState.Pending
                !pr.available -> PingState.NotAvailable
                pr.ok -> PingState.Replying
                else -> PingState.NoReply
            }
            val alive = ports.isNotEmpty() || state == PingState.Replying
            if (alive) responding++
            open += ports.size
            if (alive) list.add(HostResult(h, state, pr?.ms, ports))
            if (targets.size == 1) {
                pingState = state
                pingMs = pr?.ms
            }
        }
        hosts = list
        openPorts = open
        respondingHosts = responding
        progress = (scanDone.get() + pingDone.get()).toFloat() / maxOf(1, 2 * targets.size).toFloat()
        if (!stopping) {
            setStatus("Scanning...  ${scanDone.get()} of ${targets.size} host(s) checked", StatusKind.Info)
        }
    }

    private fun finishScan() {
        val stoppedByUser = stopping
        scanning = false
        stopping = false
        finished = true
        progress = 0f

        val n = openPorts
        val alive = respondingHosts
        val withPorts = hosts.count { it.ports.isNotEmpty() }
        if (stoppedByUser) {
            setStatus("Scan stopped. $n open port(s) found on $withPorts host(s) so far.", StatusKind.Warn)
        } else if (total == 1) {
            if (n > 0) {
                setStatus("Scan complete. $n open port(s) found. Tap a row to open it.", StatusKind.Ok)
            } else {
                setStatus("Scan complete. No open ports found.", StatusKind.Warn)
            }
        } else {
            setStatus(
                "Scan complete. $alive of $total hosts responding, $n open port(s) found on $withPorts host(s).",
                if (alive > 0) StatusKind.Ok else StatusKind.Warn
            )
        }
    }

    override fun onCleared() {
        job?.cancel()
        ioDispatcher.close()
        super.onCleared()
    }
}
