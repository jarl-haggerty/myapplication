package org.pesaran.sleeve

import android.os.StrictMode
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pesaran.sleeve.ui.theme.SleeveTheme
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
//import coil3.ImageLoader
//import coil3.ImageDecoderDecoder
//import coil3.GifDecoder
import coil3.compose.rememberAsyncImagePainter
import org.apache.commons.math3.util.FastMath.pow
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.apache.commons.math3.complex.Complex
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.pesaran.thalamus.ThalamusOuterClass
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import java.io.ByteArrayInputStream
import android.app.NotificationChannel
import android.os.Build
import android.widget.CheckBox
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.state.ToggleableState

val UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
val UART_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
val UART_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

enum class Sensor(i: Int) {
    ICM20948(2),
    INTAN_RHD2216(4),
    ADC_SINGLE(5)
}

class MainActivity : ComponentActivity() {
    val node = WaveNode()
    val clockNode = ClockNode()
    val intanNode = IntanRHDNode()
    //val intanBandPassNode = node
    val fft = FastFourierTransformer(DftNormalization.STANDARD)
    //val intanBandPassNode = BandPassNode(20.0, 250.0, intanNode)
    //val rectifierNode = RectifierNode(intanBandPassNode)
    //val lowPassNode = BandPassNode(0.0, 60.0, rectifierNode)
    val adcNode = ADCNode()
    val icmNode = ICM20948Node()
    private var error = mutableStateOf<String?>(null)
    private var status = mutableStateOf<String>("")
    private var calibrationStatus = mutableStateOf<String>("")

    val bluetoothEnableChannel = Channel<Status>()
    val bluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            if (it.resultCode == RESULT_OK) {
                bluetoothEnableChannel.send(Status(true, ""))
            } else {
                bluetoothEnableChannel.send(Status(false, "Bluetooth disabled"))
            }
        }
    }

    val scope = CoroutineScope(Dispatchers.Main)

    val permissionChannel = Channel<Status>()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            if(it.getOrDefault(android.Manifest.permission.BLUETOOTH_SCAN, false)
                && it.getOrDefault(android.Manifest.permission.BLUETOOTH_CONNECT, false)
                && it.getOrDefault(android.Manifest.permission.ACCESS_FINE_LOCATION, false)
                && it.getOrDefault(android.Manifest.permission.ACCESS_COARSE_LOCATION, false)
                && it.getOrDefault(android.Manifest.permission.POST_NOTIFICATIONS, false)) {
                permissionChannel.send(Status(true, ""))
            } else {
                permissionChannel.send(Status(false, "Bluetooth permissions denied"))
            }
        }
    }

    data class Status(val success: Boolean, val message: String)

    private suspend fun checkPermissions(): Status {
        val permissions = listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS)
        val unpermitted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }

        if(unpermitted.isEmpty()) {
            return Status(true, "")
        }

        permissionLauncher.launch(unpermitted.toTypedArray())

        val status = permissionChannel.receive()
        return status
    }

    suspend fun checkBluetooth(): Status {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter ?: return Status(false, "No Bluetooth adapter")

        if(bluetoothAdapter.isEnabled) {
            return Status(true, "")
        }
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(enableIntent)
        return bluetoothEnableChannel.receive()
    }

    val connectionProgress = mutableDoubleStateOf(0.0)

    suspend fun loop() {
        val permissionsStatus = checkPermissions()
        if (!permissionsStatus.success) {
            error.value = permissionsStatus.message
            return
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val bluetoothStatus = checkBluetooth()
        if (!bluetoothStatus.success) {
            error.value = bluetoothStatus.message
            return
        }

        val numSteps = 13.0
        var currentStep = 0.0
        val updateProgress = {
            ++currentStep
            connectionProgress.doubleValue = currentStep/numSteps
        }
        val resetProgress = {
            currentStep = 0.0
            connectionProgress.doubleValue = currentStep/numSteps
        }

        while(true) {
            resetProgress()
            val scanChannel = Channel<BluetoothDevice>()
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val bluetoothAdapter = bluetoothManager.adapter!!
            val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val deviceAddress = preferences.getString("device-address", "")
            val scanCallback = object : ScanCallback() {
                @SuppressLint("MissingPermission")
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)
                    //println("${result.device.name} ${result.device.address} ${callbackType}")
                    if((result.device.name == "NORA_INTAN_RHD_ICM") || (result.device.address == deviceAddress)) {
                        preferences.edit().putString("device-address", result.device.address).apply()
                        bluetoothAdapter.bluetoothLeScanner.stopScan(this)
                        scope.launch {
                            scanChannel.send(result.device)
                        }
                    }
                }
            }

            status.value = "Scanning"
            var device: BluetoothDevice? = null
            //if (!deviceAddress.isNullOrEmpty()) {
            //    device = bluetoothAdapter.getRemoteDevice(deviceAddress)
            //} else {
                bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
                device = scanChannel.receive()
            //}
            updateProgress()

            data class TimeSize(val time: Long, val count: Long)
            var lastTime = System.currentTimeMillis()
            val payloads = LinkedList<TimeSize>()
            val connectionChannel = Channel<Int>()
            val serviceChannel = Channel<Status>()
            val wroteChannel = Channel<Status>()
            val wroteDescriptorChannel = Channel<Status>()
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    scope.launch {
                        connectionChannel.send(newState)
                    }
                }
                @SuppressLint("MissingPermission")
                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    println("onServicesDiscovered received: $status")
                    scope.launch {
                        if(status == BluetoothGatt.GATT_SUCCESS) {
                            icmNode.reset()
                            intanNode.reset()
                            adcNode.reset()
                            serviceChannel.send(Status(true, ""))
                        } else {
                            serviceChannel.send(Status(true, "Service Discovery Failed"))
                        }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    super.onCharacteristicChanged(gatt, characteristic, value)
                    val now = System.currentTimeMillis()
                    payloads.add(TimeSize(now, value.size.toLong()))
                    while(payloads.last().time - payloads.first().time >= 1000) {
                        payloads.removeFirst()
                    }
                    if(now - lastTime >= 1000) {
                        val total = payloads.fold(0L) {
                            a, b -> a + b.count
                        }
                        val duration = (payloads.last().time - payloads.first().time).toDouble()/1000
                        println(total / duration)
                        lastTime = now
                    }

                    val blocks = Block.decodeBlockPacket(ByteBuffer.wrap(value)).asSequence().toList()
                    scope.launch {
                        blocks.forEach {
                            when (it.blockId.toInt()) {
                                2 -> icmNode.process(it)
                                4 -> intanNode.process(it)
                                5 -> adcNode.process(it)
                            }
                        }
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    super.onCharacteristicRead(gatt, characteristic, value, status)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    super.onCharacteristicWrite(gatt, characteristic, status)
                    scope.launch {
                        if(status == BluetoothGatt.GATT_SUCCESS) {
                            wroteChannel.send(Status(true, ""))
                        } else {
                            wroteChannel.send(Status(false, ""))
                        }
                    }
                    println("Wrote $status")
                }

                override fun onDescriptorWrite (gatt: BluetoothGatt,
                                                descriptor: BluetoothGattDescriptor,
                                                status: Int) {
                    super.onDescriptorWrite(gatt, descriptor, status)
                    scope.launch {
                        if(status == BluetoothGatt.GATT_SUCCESS) {
                            wroteDescriptorChannel.send(Status(true, ""))
                        } else {
                            wroteDescriptorChannel.send(Status(false, ""))
                        }
                    }
                    println("Wrote Descriptor $status")
                }
            }
            val gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            status.value = "Connecting"

            var connectionState = BluetoothProfile.STATE_DISCONNECTED
            while(connectionState != BluetoothProfile.STATE_CONNECTED) {
                connectionState = connectionChannel.receive()
            }
            updateProgress()

            status.value = "Discovering services"
            gatt.requestMtu(247)
            gatt.discoverServices()
            var servicesStatus = serviceChannel.receive()
            if(!servicesStatus.success) {
                error.value = servicesStatus.message
                return
            }
            updateProgress()

            val uartService = gatt.getService(UART_SERVICE_UUID)
            println("uartService $uartService")
            val rxCharacteristic = uartService!!.getCharacteristic(UART_RX_CHAR_UUID)
            println("rxCharacteristic $rxCharacteristic")
            val txCharacteristic = uartService!!.getCharacteristic(UART_TX_CHAR_UUID)
            println("txCharacteristic $txCharacteristic")

            val spacing = 800.milliseconds
            gatt.setCharacteristicNotification(txCharacteristic, true)
            status.value = "Enabling notifications"
            txCharacteristic.descriptors.forEach {
                gatt.writeDescriptor(it,  BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                wroteDescriptorChannel.receive()
            }
            updateProgress()
            //delay(spacing)

            val write: (suspend (Block) -> Status) = {
                val data = it.encode()
                //println(gatt)
                //println(rxCharacteristic)
                //println(data)
                gatt!!.writeCharacteristic(rxCharacteristic!!, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                wroteChannel.receive()
            }

            //gatt.setCharacteristicNotification(txCharacteristic, false)
            //gatt.writeCharacteristic(txCharacteristic!!, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            delay(spacing)
            println(1)
            status.value = "Setting ICM channel mask"
            var writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(2, 0, 0, 0, 63))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(2)
            //delay(1.seconds)
            status.value = "Setting INTAN channel mask"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(4, 0, 0, -1, -1))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(3)
            //delay(1.seconds)
            status.value = "Setting ADC channel mask"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(5, 0, 0, 0, 1))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(4)
            //delay(1.seconds)

            status.value = "Setting ICM sample rate"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(2, 0))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(5)
            //delay(1.seconds)
            status.value = "Setting INTAN sample rate"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(4, 19))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(6)
            //delay(1.seconds)
            status.value = "Setting ADC sample rate"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(5, 0))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(7)
            //delay(1.seconds)

            status.value = "Enabling ICM"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(2, 1))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(1)
            //delay(1.seconds)
            status.value = "Enabling INTAN"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(4, 1))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println(1)
            //delay(1.seconds)
            status.value = "Enabling ADC"
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(5, 1))))
            if(!writeStatus.success) {
                continue;
            }
            updateProgress()
            delay(spacing)
            println("Pause")
            //gatt.readCharacteristic(txCharacteristic)
            //delay(1.seconds)
            println("Streaming")

            status.value = "Connected"
            while(connectionState == BluetoothProfile.STATE_CONNECTED) {
                //status.value = "Ready $connectionState"
                println(connectionState)
                connectionState = connectionChannel.receive()
            }
            status.value = "Disconnected"
        }
    }

    fun clearData() {
        val filesDir = getExternalFilesDir(null)
        val recordingDir = File(filesDir, "recording")
        recordingDir?.listFiles()?.forEach {
            println(it)
            it.delete()
        }
    }

    fun shareData() {
        val uris = ArrayList<Uri>()
        val filesDir = getExternalFilesDir(null)
        val recordingDir = File(filesDir, "recording")
        recordingDir.listFiles()?.forEach {
            val uri = FileProvider.getUriForFile(
                this,
                applicationContext.packageName + ".provider",
                it
            )
            uris.add(uri)
        }
        println(uris)

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            type = "application/octet-stream"
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, null))
    }

    //var snr = mutableStateOf(listOf<List<String>>(listOf("", "ch0", "ch1", "ch2"), listOf("1", "1.0", "10.0", "100.0"), listOf("2", "1000.0", "10000.0", "100000.0")))
    var snr = mutableStateOf(listOf<List<String>>())

    @Serializable
    data class MyModel(val bias: DoubleArray, val coef: Array<DoubleArray>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MyModel

            if (!bias.contentEquals(other.bias)) return false
            if (!coef.contentDeepEquals(other.coef)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bias.contentHashCode()
            result = 31 * result + coef.contentDeepHashCode()
            return result
        }
    }

    var model: MyModel? = null

    val getModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        if(it == null) {
            return@registerForActivityResult
        }
        val bytes = applicationContext.contentResolver.openInputStream(it)!!.readAllBytes()
        val text = String(bytes, StandardCharsets.UTF_8)

        model = Json.decodeFromString(MyModel.serializer(), text)

        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        preferences.edit(commit = true) {
            putString("model", text)
        }
    }

    fun importModel() {
        getModelLauncher.launch(arrayOf("application/json"))
    }

    val dataNode = intanNode

    suspend fun testModel(prompt: MutableState<Int?>) {
        if(model == null) {
            notice.value = "No Model loaded"
            return
        }
        val model = this.model!!
        if(model.bias.isEmpty()) {
            notice.value = "No biases in model"
            return
        }
        if(model.coef.isEmpty()) {
            notice.value = "No coefficients in model"
            return
        }
        if(model.bias.size != model.coef.size) {
            notice.value = "Biases don't match coefficients in model"
            return
        }
        model.coef.forEach {
            if(it.isEmpty()) {
                notice.value = "Coefficient list in model is empty"
                return@testModel
            }
        }

        //calibrationStatus.value = "Calibrating"

        val samples = mutableListOf<MutableList<Double>>()
        val sampleIntervals = mutableListOf<Duration>()
        val channelNames = mutableListOf<String>()
        val node = dataNode

        val connection = node.ready.connect {
            if(!node.hasAnalogData()) {
                return@connect
            }

            while(samples.size < node.numChannels()) {
                if(node.name(samples.size) == "loss") {
                    break
                }
                sampleIntervals.add(node.sampleInterval(samples.size))
                channelNames.add(node.name(samples.size))
                samples.add(mutableListOf<Double>())
                //squareSums.add(0.0)
                //counts.add(0)
            }
            for(i in 0..<samples.size) {
                //var newSquareSum = 0.0
                if(node.dataType() == TimeSeriesNode.DataType.DOUBLE) {
                    val newSamples = node.doubles(i)
                    //counts[i] += newSamples.remaining()
                    val channelSamples = samples[i]
                    while(newSamples.hasRemaining()) {
                        channelSamples.add(newSamples.get())
                        //newSquareSum += newSamples.get().pow(2.0)
                    }
                } else {
                    val newSamples = node.shorts(i)
                    //counts[i] += newSamples.remaining()
                    val channelSamples = samples[i]
                    while(newSamples.hasRemaining()) {
                        channelSamples.add(newSamples.get().toDouble())
                        //newSquareSum += newSamples.get().pow(2.0)
                    }
                }
                //squareSums[i] += newSquareSum
            }
        }

        val filter = fun(it: MutableList<Double>, samplePeriod: Duration): DoubleArray {
            var pow2 = 1
            while(2*pow2 <= it.size) {
                pow2 *= 2
            }
            //val lower2 = log2(it.size.toDouble())
            //val tail = pow(2.0, lower2).toInt()
            val timeDomain = it.subList(it.size - pow2, it.size).toDoubleArray()
            val frequencyDomain = fft.transform(timeDomain, TransformType.FORWARD)
            val sampleFrequency = 1e9/samplePeriod.inWholeNanoseconds
            val nyquistFrequency = sampleFrequency/2
            val start = (frequencyDomain.size/2 * (50.0/nyquistFrequency)).toInt()
            val end = (frequencyDomain.size/2 * (450.0/nyquistFrequency)).toInt()
            /*val N = 2*(end-start+1)
            val positive = frequencyDomain.slice(start..end).fold(0.0) { acc, i ->
                acc + i.real*i.real + i.imaginary*i.imaginary
            }
            val total = frequencyDomain.slice((frequencyDomain.size-1-end)..(frequencyDomain.size-1-start)).fold(positive) { acc, i ->
                acc + i.real*i.real + i.imaginary*i.imaginary
            }
            return sqrt(total/(N*N))*/
            for (i in 0..<start) {
                frequencyDomain[i] = Complex.ZERO
                frequencyDomain[frequencyDomain.size-1-i] = Complex.ZERO
            }
            for (i in (end+1)..<(frequencyDomain.size-1-end)) {
                frequencyDomain[i] = Complex.ZERO
            }
            return fft.transform(frequencyDomain, TransformType.INVERSE).map {
                it.real
            }.toDoubleArray()
        }

        val scale = {it: List<Double> ->
            val mean = it.sum()/it.size
            val std = sqrt(it.sumOf { pow(it - mean, 2) } / it.size)
            it.map { (it - mean)/std }
        }

        val threshold = .001
        val drawables = listOf(
            R.drawable.calibration_1,
            R.drawable.calibration_2,
            R.drawable.calibration_3,
            R.drawable.calibration_4,
            R.drawable.calibration_5,
            R.drawable.calibration_6,
            R.drawable.calibration_7,
        )

        try {
            while(true) {
                samples.clear()
                sampleIntervals.clear()
                channelNames.clear()
                delay(1.seconds)
                if(samples.size < 12) {
                    continue
                }
                val relevant = samples.slice(4..11)
                val filtered = relevant.zip(sampleIntervals).map {filter(it.first, it.second)}
                val features = filtered.map {
                    val middle = it.slice(it.size/4..3*it.size/4)
                    val scaled = scale(middle)

                    //The mean, rms and variance of a series scaled to mean=0 and std=1 is just 0, 1, 1.
                    val mean = 0.0//scaled.sum() / scaled.size
                    val rms = 1.0//sqrt(scaled.sumOf {it*it} / scaled.size)
                    val variance = 1.0//scaled.sumOf { pow(it-mean,2) } / scaled.size

                    val meanAbs = scaled.sumOf{abs(it)}

                    val diff1 = scaled.slice(1..<scaled.size-1).zip(scaled.slice(0..<scaled.size-2)).map{it.first-it.second}
                    val diff2 = scaled.slice(1..<scaled.size-1).zip(scaled.slice(2..<scaled.size)).map{it.first-it.second}
                    val ssc = diff1.zip(diff2).sumOf {
                        if(it.first*it.second >= threshold) {
                            1.0
                        } else {
                            0.0
                        }
                    }

                    val lag = scaled.slice(0..<scaled.size-1)
                    val lead = scaled.slice(1..<scaled.size)
                    val zc = lag.zip(lead).sumOf {
                        if(((it.first > 0) xor (it.second > 0)) and (abs(it.first - it.second) >= threshold)) {
                            1.0
                        } else {
                            0.0
                        }
                    }

                    val wl = lag.zip(lead).sumOf {
                        abs(it.first - it.second)
                    }

                    listOf(rms, variance, meanAbs, ssc, zc, wl)
                }.flatten()

                val scores = model.bias.zip(model.coef).map {
                    it.first + it.second.zip(features).sumOf {it.first*it.second}
                }
                val gesture = (0..<scores.size).maxBy { scores[it] }
                prompt.value = drawables[gesture % drawables.size]
                println("MODEL $gesture")
            }
        } finally {
            prompt.value = null
            connection.disconnect()
        }
    }

    suspend fun calibration(prompt: MutableState<Int?>) {
        calibrationStatus.value = "Calibrating"
        /*var channel = 0
        var elapsed = 0.seconds
        var window = LinkedList<Double>()
        var windowSize = 100.milliseconds
        var sum = 0.0
        var count = 0L

        var powerSum = 0.0
        var powerCount = 0.0
        var restPower = 0.0
        var activePower = 0.0
        var snr = 0.0
        val clear = {
            channel = 0
            elapsed = 0.seconds
            window = LinkedList<Double>()
            sum = 0.0
            count = 0L
            powerSum = 0.0
            powerCount = 0.0
        }*/

        //snr.clear()

        val samples = mutableListOf<MutableList<Double>>()
        val sampleIntervals = mutableListOf<Duration>()
        val channelNames = mutableListOf<String>()
        val node = dataNode

        val connection = node.ready.connect {
            if(!node.hasAnalogData()) {
                return@connect
            }

            while(samples.size < node.numChannels()) {
                if(node.name(samples.size) == "loss") {
                    break
                }
                sampleIntervals.add(node.sampleInterval(samples.size))
                channelNames.add(node.name(samples.size))
                samples.add(mutableListOf<Double>())
                //squareSums.add(0.0)
                //counts.add(0)
            }
            for(i in 0..<samples.size) {
                //var newSquareSum = 0.0
                if(node.dataType() == TimeSeriesNode.DataType.DOUBLE) {
                    val newSamples = node.doubles(i)
                    //counts[i] += newSamples.remaining()
                    val channelSamples = samples[i]
                    while(newSamples.hasRemaining()) {
                        channelSamples.add(newSamples.get())
                        //newSquareSum += newSamples.get().pow(2.0)
                    }
                } else {
                    val newSamples = node.shorts(i)
                    //counts[i] += newSamples.remaining()
                    val channelSamples = samples[i]
                    while(newSamples.hasRemaining()) {
                        channelSamples.add(newSamples.get().toDouble())
                        //newSquareSum += newSamples.get().pow(2.0)
                    }
                }
                //squareSums[i] += newSquareSum
            }
        }

        /*val computeSnr = {
            val cvs = samples.map {
                val mean = it.average()
                val variance = it.map { pow(it - mean, 2) }.sum() / mean
                val std = sqrt(variance)
                std/mean
            }
            val medians = samples.map {
                val sorted = it.sorted()
                sorted[sorted.size/2]
            }
            val ratios = medians.zip(cvs).map { it.first / it.second }
            ratios.max()
        }*/
        try {
            val drawables = listOf(
                R.drawable.calibration_1,
                R.drawable.calibration_2,
                R.drawable.calibration_3,
                R.drawable.calibration_4,
                R.drawable.calibration_5,
                R.drawable.calibration_6,
                R.drawable.calibration_7
            )
            val gestureNames = listOf(
                "g1",
                "g2",
                "g3",
                "g4",
                "g5",
                "g6",
                "g7"
            )

            val rms = fun(it: MutableList<Double>, samplePeriod: Duration): Double {
                var pow2 = 1
                while(2*pow2 <= it.size) {
                    pow2 *= 2
                }
                //val lower2 = log2(it.size.toDouble())
                //val tail = pow(2.0, lower2).toInt()
                val timeDomain = it.subList(it.size - pow2, it.size).toDoubleArray()
                val frequencyDomain = fft.transform(timeDomain, TransformType.FORWARD)
                val sampleFrequency = 1e9/samplePeriod.inWholeNanoseconds
                val nyquistFrequency = sampleFrequency/2
                val start = (frequencyDomain.size/2 * (20.0/nyquistFrequency)).toInt()
                val end = (frequencyDomain.size/2 * (250.0/nyquistFrequency)).toInt()
                /*val N = 2*(end-start+1)
                val positive = frequencyDomain.slice(start..end).fold(0.0) { acc, i ->
                    acc + i.real*i.real + i.imaginary*i.imaginary
                }
                val total = frequencyDomain.slice((frequencyDomain.size-1-end)..(frequencyDomain.size-1-start)).fold(positive) { acc, i ->
                    acc + i.real*i.real + i.imaginary*i.imaginary
                }
                return sqrt(total/(N*N))*/
                for (i in 0..<start) {
                    frequencyDomain[i] = Complex.ZERO
                    frequencyDomain[frequencyDomain.size-1-i] = Complex.ZERO
                }
                for (i in (end+1)..<(frequencyDomain.size-1-end)) {
                    frequencyDomain[i] = Complex.ZERO
                }
                val rectified = fft.transform(frequencyDomain, TransformType.INVERSE).map {
                    abs(it.real)
                }.toDoubleArray()

                val frequencyDomain2 = fft.transform(rectified, TransformType.FORWARD)
                val end2 = (frequencyDomain2.size/2 * (60.0/nyquistFrequency)).toInt()
                for (i in (end2+1)..<(frequencyDomain2.size-1-end2)) {
                    frequencyDomain2[i] = Complex.ZERO
                    frequencyDomain2[frequencyDomain2.size-1-i] = Complex.ZERO
                }

                val N = frequencyDomain2.size - 2*(end2+1)
                val positive = frequencyDomain2.slice(0..end2).fold(0.0) { acc, i ->
                    acc + i.real*i.real + i.imaginary*i.imaginary
                }
                val total = frequencyDomain2.slice((frequencyDomain2.size-1-end2)..<frequencyDomain2.size).fold(positive) { acc, i ->
                    acc + i.real*i.real + i.imaginary*i.imaginary
                }
                //application manager skips square root
                return total/(N*N)
                //return sqrt(total/(N*N))
            }

            /*val rms = { it: DoubleArray ->
                val squareSum = it.fold(0.0) { acc, i ->
                    acc + i*i
                }
                sqrt(squareSum/it.size)
            }*/

            val temp = drawables.zip(gestureNames).map {
                samples.clear()
                sampleIntervals.clear()
                channelNames.clear()
                prompt.value = R.drawable.rest
                delay(5.seconds)
                val start = System.nanoTime()
                val restRms = samples.zip(sampleIntervals).map { rms(it.first, it.second) }
                println("rest " + (System.nanoTime() - start)/1e9 + " " + restRms.toString())

                samples.clear()
                sampleIntervals.clear()
                channelNames.clear()
                prompt.value = it.first
                delay(5.seconds)
                val start2 = System.nanoTime()
                val activeRms = samples.zip(sampleIntervals).map { rms(it.first, it.second) }
                println("active " + (System.nanoTime() - start2)/1e9 + " " + activeRms.toString())

                val temp = activeRms.zip(restRms).map {
                    10*log10(it.first / it.second)
                }
                listOf(it.second) + temp.map {String.format("%.4f", it)} + listOf(String.format("%.4f", temp.max()))
            }
            snr.value = listOf(listOf("") + channelNames + listOf("max")) + temp
            /*clear()
            channel = 6
            prompt.value = R.drawable.calibration_1
            delay(5.seconds)
            activePower = sqrt(powerSum.toDouble() / powerCount)
            snr = 10*log10(activePower / restPower)
            if(snr < 14) {
                calibrationStatus.value = "Bad SNR for channel ${channel+1}, $snr"
                return
            }
            calibrationStatus.value = "Good SNR for channel ${channel+1}, $snr"

            prompt.value = R.drawable.rest
            delay(5.seconds)
            prompt.value = R.drawable.calibration_2
            delay(5.seconds)
            prompt.value = R.drawable.rest
            delay(5.seconds)
            prompt.value = R.drawable.calibration_3
            delay(5.seconds)
            prompt.value = R.drawable.rest
            delay(5.seconds)
            prompt.value = R.drawable.calibration_4
            delay(5.seconds)
            prompt.value = R.drawable.rest
            delay(5.seconds)
            prompt.value = R.drawable.calibration_5
            delay(5.seconds)
            prompt.value = R.drawable.rest
            delay(5.seconds)
            prompt.value = R.drawable.calibration_6
            delay(5.seconds)
            prompt.value = R.drawable.rest
            delay(5.seconds)
            prompt.value = R.drawable.calibration_7
            delay(5.seconds)*/
        } finally {
            prompt.value = null
            connection.disconnect()
        }
    }

    val notice = mutableStateOf("")
    var multicastThread: Thread? = null
    val showSurvey = mutableStateOf(false)

    fun pollShowSurvey() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        this.showSurvey.value = preferences.getBoolean("show-survey", false)
    }

    override fun onResume() {
        super.onResume()
        pollShowSurvey()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).detectLeakedClosableObjects().build()
        )

        val storage = Storage(this)
        storage.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel.
            val mChannel = NotificationChannel("SLEEVE", "Sleeve", NotificationManager.IMPORTANCE_MAX)
            mChannel.description = "Sleeve Survery"
            // Register the channel with the system. You can't change the importance
            // or other notification behaviors after this.
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)
        }

        multicastThread = thread {
            val socket = MulticastSocket(50091)
            val group = InetAddress.getByName("224.0.0.91")
            socket.joinGroup(group)
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            while(!isFinishing) {
                socket.receive(packet)
                val message = ThalamusOuterClass.StorageRecord.parseFrom(ByteArrayInputStream(packet.data, 0, packet.length))
                val now = System.nanoTime()
                val remoteTime = message.time
                val recordBuilder = message.toBuilder()
                recordBuilder.setTime(now).analogBuilder.setTime(now).setRemoteTime(remoteTime)
                val newMessage = recordBuilder.build()
                println(newMessage)
                storage.log(newMessage)
            }
            socket.leaveGroup(group)
            socket.close()
        }

        /*SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .crossfade(true)
                .build()
        }*/

        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        val workRequest = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val surveyRequest = PeriodicWorkRequestBuilder<SurveyWorker>(15, TimeUnit.MINUTES)
            .build()

        val workInfo = WorkManager.getInstance(this).getWorkInfosForUniqueWork("sleeve-data-upload")
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("sleeve-data-upload",
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, workRequest)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("sleeve-survey",
            ExistingPeriodicWorkPolicy.UPDATE, surveyRequest)

        scope.launch {
            loop()
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        var uuid = preferences.getString("uuid", "")
        if (uuid == null || uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString()
            preferences.edit(commit = true) { putString("uuid", uuid) }
        }

        val modelText = preferences.getString("model", "")!!
        if(!modelText.isEmpty()) {
            model = Json.decodeFromString(MyModel.serializer(), modelText)
        }

        var value = mutableIntStateOf(0)
        var recording = mutableStateOf(false)
        var confirmClearState = mutableStateOf(false)
        val promptState = mutableStateOf<Int?>(null)
        val recordSeconds = mutableStateOf(preferences.getInt("record-seconds", 0).seconds)
        //storage.add("Wave", node)
        storage.add("Clock", clockNode)
        storage.add("Intan", intanNode)
        storage.add("ICM", icmNode)
        storage.add("ADC", adcNode)

        node.start()
        clockNode.start()
        val toggle = {
            node.toggle()
            //if(node.running) {
            //    storage.start()
            //} else {
           //     storage.stop()
            //}
        }

        scope.launch {
            while(true) {
                delay(1.seconds)
                if(recording.value) {
                    val seconds = preferences.getInt("record-seconds", 0)
                    preferences.edit(commit = true) {
                        putInt("record-seconds", seconds + 1)
                    }
                    recordSeconds.value = (seconds+1).seconds
                }
                pollShowSurvey();
            }
        }

        enableEdgeToEdge()
        setContent {
            var count by remember { value }
            var error by remember { error }
            var status by remember { status }
            var connectionProgress by remember {connectionProgress}
            var calibrationStatus by remember { calibrationStatus }
            var recording by remember { recording }
            var confirmClear by remember { confirmClearState }
            var expanded by remember { mutableStateOf(false) }
            var selectPlot by remember { mutableStateOf("Intan") }
            var prompt by remember { promptState }
            var promptJob by remember {mutableStateOf<Job?>(null)}
            var modelJob by remember {mutableStateOf<Job?>(null)}
            var snr by remember { snr }
            var recordSeconds by remember { recordSeconds }
            var notice by remember {notice}
            //var scope = rememberCoroutineScope()
            var clipboard = LocalClipboard.current
            var showSurvey by remember { showSurvey }

            /*val imageLoader = ImageLoader.Builder(applicationContext)
                .components {
                    if (SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()*/

            SleeveTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if(showSurvey) {
                        Survey(innerPadding, storage) {
                            val preferences =
                                PreferenceManager.getDefaultSharedPreferences(applicationContext)
                            preferences.edit(commit = true) { putBoolean("show-survey", false) }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (error != null) {
                                item {
                                    Text(
                                        error!!,
                                        modifier = Modifier.padding(innerPadding),
                                        color = Color.Red
                                    )
                                }
                            } /*else if (status != null) {
                                Text(status!!, modifier = Modifier.padding(innerPadding));
                            } else {*/

                            item { Text("UUID: $uuid", modifier = Modifier.padding(innerPadding)) }
                            item {
                                Button(onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipData.newPlainText(uuid, uuid).toClipEntry()
                                        )
                                    }
                                }) {
                                    Text("Copy UUID")
                                }
                            }

                            item {
                                Button(onClick = {
                                    val preferences =
                                        PreferenceManager.getDefaultSharedPreferences(applicationContext)
                                    preferences.edit(commit = true) { putBoolean("show-survey", true) }
                                }) {
                                    Text("Do Survey")
                                }
                            }

                            item {
                                Text(
                                    "Connection Status: $status",
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                            item {
                                LinearProgressIndicator(
                                    progress = { connectionProgress.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            item {
                                Text(
                                    "Calibration Status: $calibrationStatus",
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(onClick = {
                                        confirmClearState.value = true
                                    }) { Text("Clear Data") }
                                    Button(onClick = { shareData() }) { Text("Share Data") }
                                }
                            }

                            if (confirmClear) {
                                item {
                                    AlertDialog(
                                        onDismissRequest = { confirmClearState.value = false },
                                        confirmButton = {
                                            Button(onClick = {
                                                clearData()
                                                confirmClearState.value = false
                                            }) { Text("Confirm") }
                                        },
                                        dismissButton = {
                                            Button(onClick = {
                                                confirmClearState.value = false
                                            }) { Text("Cancel") }
                                        },
                                        title = { Text("Clear Data") },
                                        text = { Text("Clear Data?") })
                                }
                            }

                            if (!notice.isEmpty()) {
                                item {
                                    Dialog(
                                        onDismissRequest = { notice = "" }) {
                                        Text(notice)
                                    }
                                }
                            }

                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Record")
                                    Checkbox(
                                        checked = recording,
                                        onCheckedChange = {
                                            recording = it
                                            storage.recording = it
                                        }
                                    )
                                    Text(
                                        String.format(
                                            "%d:%02d:%02d",
                                            recordSeconds.inWholeHours,
                                            recordSeconds.inWholeMinutes % 60,
                                            recordSeconds.inWholeSeconds % 60
                                        )
                                    )
                                    Button(onClick = {
                                        val seconds = preferences.getInt("record-seconds", 0)
                                        preferences.edit(commit = true) {
                                            putInt("record-seconds", 0)
                                        }
                                        recordSeconds = 0.seconds
                                    }) { Text("Clear") }
                                }
                            }

                            item {
                                Column() {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) { Text("SNR:") }
                                        Box(modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)) {
                                            Button(onClick = {
                                                if (!(promptJob?.isActive ?: false)) {
                                                    promptJob = scope.launch {
                                                        calibration(promptState)
                                                    }
                                                } else {
                                                    promptJob?.let { it.cancel() }
                                                }
                                            }) {
                                                Text(
                                                    if (!(promptJob?.isActive ?: false)) {
                                                        "Measure"
                                                    } else {
                                                        "Cancel"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                        ) { Text("Model:") }
                                        Box(modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)) {
                                            Button(onClick = {
                                                if (!(modelJob?.isActive ?: false)) {
                                                    modelJob = scope.launch {
                                                        testModel(promptState)
                                                    }
                                                } else {
                                                    modelJob?.let { it.cancel() }
                                                }
                                            }) {
                                                Text(
                                                    if (!(modelJob?.isActive ?: false)) {
                                                        "Test"
                                                    } else {
                                                        "Cancel"
                                                    }
                                                )
                                            }
                                        }
                                        Box(modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)) {
                                            Button(onClick = {
                                                importModel()
                                            }) { Text("Import") }
                                        }
                                    }
                                }
                            }

                            item {
                                Box {
                                    Button(onClick = { expanded = !expanded }) {
                                        Text(selectPlot)
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Wave") },
                                            onClick = {
                                                expanded = false
                                                selectPlot = "Wave"
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("ICM") },
                                            onClick = {
                                                expanded = false
                                                selectPlot = "ICM"
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Intan") },
                                            onClick = {
                                                expanded = false
                                                selectPlot = "Intan"
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("ADC") },
                                            onClick = {
                                                expanded = false
                                                selectPlot = "ADC"
                                            }
                                        )
                                    }
                                }
                            }

                            //if(prompt != null) {
                            stickyHeader {
                                prompt?.let {
                                    Image(
                                        painter = rememberAsyncImagePainter(it), "",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black)
                                    )
                                }
                            }
                            if (snr.isNotEmpty()) {
                                /*for (i in 0..<snr[0].size) {
                                    Row() {
                                        snr.forEach {
                                            Text(String.format("%.2f", it[i]) + " ")
                                        }
                                    }
                                }*/
                                item {
                                    Column {
                                        for (i in 0..<snr[0].size) {
                                            Row {
                                                for (j in 0..<snr.size) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .weight(1f)
                                                            .border(BorderStroke(1.dp, Color.White))
                                                    ) {
                                                        Text(" " + snr[j][i])
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                /*LazyVerticalGrid(columns=GridCells.Fixed(snr.size)) {
                                    items(snr.map {it.size}.sum()) { it ->
                                        Text(" " + snr[it % snr.size][it/snr.size], modifier = Modifier.border(BorderStroke(1.dp, Color.White)))
                                    }
                                    //snr.forEach {
                                        //Column() {
                                        //    it.forEach {
                                        //        Box(modifier = Modifier.border(BorderStroke(2.dp, Color.White))) {
                                    //                Text(String.format("%.2f", it))
                                    //            }
                                         //   }
                                        //}
                                    //}
                                }*/
                                item {
                                    Button(onClick = {
                                        snr = listOf<List<String>>()
                                    }) { Text("Clear SNR") }
                                }
                            }
                            //} else {
                            item {
                                if (selectPlot == "Wave") {
                                    Graph(modifier = Modifier.fillMaxSize(), node)
                                    //} else if(selectPlot == "Band Pass") {
                                    //    Graph(modifier = Modifier.fillMaxSize(), intanBandPassNode)
                                    //} else if(selectPlot == "Rectified") {
                                    //    Graph(modifier = Modifier.fillMaxSize(), rectifierNode)
                                    //} else if(selectPlot == "Low Pass") {
                                    //   Graph(modifier = Modifier.fillMaxSize(), lowPassNode)
                                } else if (selectPlot == "ICM") {
                                    Graph(modifier = Modifier.fillMaxSize(), icmNode)
                                } else if (selectPlot == "Intan") {
                                    Graph(modifier = Modifier.fillMaxSize(), intanNode)
                                } else if (selectPlot == "ADC") {
                                    Graph(modifier = Modifier.fillMaxSize(), adcNode)
                                }
                            }
                            //}


                            //Button(onClick = {toggle()}) { Text(count.toString())};
                            //}
                        }
                    }
                }
            }
        }
    }
}

val COLORS = arrayOf(
    Color.Blue,
    Color.Green,
    Color.Red,
    Color.Cyan,
    Color.Magenta,
    Color.Yellow,
    Color.Gray
)

@Composable
fun SliderRow(question: String, state: Float?, setState: (Float) -> Unit, min: Float, max: Float) {
    val range = max - min
    Text("$question ${state?.toInt() ?: "No Answer"}")
    Slider(
        value = (state ?: 0f)/range + min,
        onValueChange = { setState(range*it + min) }
    )
}

@Composable
fun CheckBoxRow(question: String, state: ToggleableState, setState: (ToggleableState) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(question)
        TriStateCheckbox(
            state = state,
            onClick = {
                setState(when (state) {
                    ToggleableState.Indeterminate -> {
                        ToggleableState.On
                    }
                    ToggleableState.On -> {
                        ToggleableState.Off
                    }
                    ToggleableState.Off -> {
                        ToggleableState.On
                    }
                })
            }
        )
        Text(when (state) {
            ToggleableState.Indeterminate -> {
                "No answer"
            }
            ToggleableState.On -> {
                "Yes"
            }
            ToggleableState.Off -> {
                "No"
            }
        })
    }
}

@Composable
fun Survey(innerPadding: PaddingValues, storage: Storage, onSubmit: () -> Unit) {
    var (distress, setDistress) = remember { mutableStateOf<Float?>(null) }
    var (performingManualRitual, setPerformingManualRitual) = remember { mutableStateOf<ToggleableState>(ToggleableState.Indeterminate) }
    var (performingMentalRitual, setPerformingMentalRitual) = remember { mutableStateOf<ToggleableState>(ToggleableState.Indeterminate) }
    var (manualRitualMinutes, setManualRitualMinutes) = remember { mutableStateOf<Float?>(null) }
    var (mentalRitualMinutes, setMentalRitualMinutes) = remember { mutableStateOf<Float?>(null) }

    var (manualRitualUrge, setManualRitualUrge) = remember { mutableStateOf<Float?>(null) }
    var (manualRitualResistance, setManualRitualResistance) = remember { mutableStateOf<Float?>(null) }

    var (mentalRitualUrge, setMentalRitualUrge) = remember { mutableStateOf<Float?>(null) }
    var (mentalRitualResistance, setMentalRitualResistance) = remember { mutableStateOf<Float?>(null) }

    val toggleToNumber = { state: ToggleableState ->
        when (state) {
            ToggleableState.Indeterminate -> {
                -1
            }
            ToggleableState.On -> {
                1
            }
            ToggleableState.Off -> {
                0
            }
        }
    }

    LazyColumn(modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)) {
        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("Right now, what is your distress?", distress, setDistress, 0f, 100f)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {CheckBoxRow("Right now are you engaging\nin any manual compulsion/ritual?", performingManualRitual, setPerformingManualRitual)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("In the past hour, how many minutes have you spent doing any manual compulsion/ritual?", manualRitualMinutes, setManualRitualMinutes, 0f, 60f)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("Right now, what are your urges to engage in any manual compulsion/ritual?", manualRitualUrge, setManualRitualUrge, 0f, 100f)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("Right now, how much effort are you making to resist any manual compulsion/rituals?", manualRitualResistance, setManualRitualResistance, 0f, 100f)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {CheckBoxRow("Right now, are you engaging in any mental compulsion/ritual?", performingMentalRitual, setPerformingMentalRitual)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("In the past hour, how many minutes have you spent doing any mental compulsion/ritual?", mentalRitualMinutes, setMentalRitualMinutes, 0f, 60f)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("Right now, what are your urges to engage in any mental compulsion/ritual?", mentalRitualUrge, setMentalRitualUrge, 0f, 100f)}

        item {HorizontalDivider(thickness = 2.dp)}
        item {SliderRow("Right now, how much effort are you making to resist any mental compulsion/rituals?", mentalRitualResistance, setMentalRitualResistance, 0f, 100f)}

        item {Button(onClick = {
            val now = System.nanoTime()

            val recordBuilder = ThalamusOuterClass.StorageRecord.newBuilder()
            recordBuilder.setNode("Survey")
            recordBuilder.setTime(now)
            val analogBuilder = recordBuilder.analogBuilder
            analogBuilder.setTime(now)
            val data = listOf(
                (distress?.toInt() ?: -1),
                toggleToNumber(performingManualRitual),
                (manualRitualMinutes?.toInt() ?: -1),
                (manualRitualUrge?.toInt() ?: -1),
                (manualRitualResistance?.toInt() ?: -1),
                toggleToNumber(performingMentalRitual),
                (mentalRitualMinutes?.toInt() ?: -1),
                (mentalRitualUrge?.toInt() ?: -1),
                (mentalRitualResistance?.toInt() ?: -1),
            )
            analogBuilder.addAllIntData(data)
            analogBuilder.setIsIntData(true)

            val newMessage = recordBuilder.build()
            println(newMessage)
            storage.log(newMessage)

            onSubmit()
        }) { Text("Submit") }}
    }
}

@Composable
fun <T> Graph(modifier: Modifier = Modifier, node: T) where T : Node, T: TimeSeriesNode {
    var invalidate by remember { mutableIntStateOf(0) }
    val signals by remember { mutableStateOf<MutableList<SignalPlot>>(mutableListOf<SignalPlot>()) }
    val textMeasurer = rememberTextMeasurer()
    LaunchedEffect(Unit) {
        val connection = node.ready.connect {
            if(node.hasAnalogData()) {
                (0..<node.numChannels()).forEach {
                    while(signals.size <= it) {
                        signals.add(SignalPlot(node.name(it)))
                    }
                    val plot = signals[it]
                    val interval = node.sampleInterval(it)
                    if(node.dataType() == TimeSeriesNode.DataType.DOUBLE) {
                        val data = node.doubles(it)
                        while(data.remaining() > 0) {
                            val sample = data.get()
                            plot.addSignal(sample, interval.inWholeNanoseconds)
                        }
                    } else if (node.dataType() == TimeSeriesNode.DataType.SHORT) {
                        val data = node.shorts(it)
                        while(data.remaining() > 0) {
                            val sample = data.get()
                            plot.addSignal(sample.toDouble(), interval.inWholeNanoseconds)
                        }
                    }
                }
            }
        }

        try {
            while(true) {
                delay(16)
                ++invalidate
            }
        } finally {
            connection.disconnect()
        }

    }

    Canvas(modifier=modifier.height((LocalConfiguration.current.screenHeightDp).dp)) {
        invalidate.apply {}
        val count = signals.size
        val pixelSlice = (size.height-100)/count
        drawRect(Color.Black, Offset(0F, 0F), Size(size.width, size.height))
        signals.forEachIndexed { i, it ->
            it.draw(this, textMeasurer, Rect(0F, i*pixelSlice, size.width, (i+1)*pixelSlice), COLORS[i % COLORS.size])
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SleeveTheme {
        Greeting("Android")
    }
}