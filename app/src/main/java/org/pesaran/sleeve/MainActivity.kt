package org.pesaran.sleeve

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import java.lang.Math.pow
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.apache.commons.math3.complex.Complex
import kotlin.math.abs

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
                && it.getOrDefault(android.Manifest.permission.ACCESS_COARSE_LOCATION, false)) {
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
            android.Manifest.permission.ACCESS_COARSE_LOCATION)
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

        while(true) {
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

            gatt.requestMtu(247)
            gatt.discoverServices()
            var servicesStatus = serviceChannel.receive()
            if(!servicesStatus.success) {
                error.value = servicesStatus.message
                return
            }

            val uartService = gatt.getService(UART_SERVICE_UUID)
            println("uartService $uartService")
            val rxCharacteristic = uartService!!.getCharacteristic(UART_RX_CHAR_UUID)
            println("rxCharacteristic $rxCharacteristic")
            val txCharacteristic = uartService!!.getCharacteristic(UART_TX_CHAR_UUID)
            println("txCharacteristic $txCharacteristic")

            val spacing = 800.milliseconds
            gatt.setCharacteristicNotification(txCharacteristic, true)
            txCharacteristic.descriptors.forEach {
                gatt.writeDescriptor(it,  BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                wroteDescriptorChannel.receive()
            }
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
            var writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(2, 0, 0, 0, 63))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(2)
            //delay(1.seconds)
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(4, 0, 0, -1, -1))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(3)
            //delay(1.seconds)
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(5, 0, 0, 0, 1))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(4)
            //delay(1.seconds)

            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(2, 0))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(5)
            //delay(1.seconds)
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(4, 19))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(6)
            //delay(1.seconds)
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(5, 0))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(7)
            //delay(1.seconds)

            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(2, 1))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(1)
            //delay(1.seconds)
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(4, 1))))
            if(!writeStatus.success) {
                continue;
            }
            delay(spacing)
            println(1)
            //delay(1.seconds)
            writeStatus = write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(5, 1))))
            if(!writeStatus.success) {
                continue;
            }
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

    var snr = mutableStateOf(listOf<List<Double>>())

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
        val node = this.intanNode

        val connection = node.ready.connect {
            if(!node.hasAnalogData()) {
                return@connect
            }

            while(samples.size < node.numChannels()) {
                sampleIntervals.add(node.sampleInterval(samples.size))
                samples.add(mutableListOf<Double>())
                //squareSums.add(0.0)
                //counts.add(0)
            }
            for(i in 0..<node.numChannels()) {
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
                R.drawable.calibration_7,
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

            snr.value = drawables.map {
                samples.clear()
                sampleIntervals.clear()
                prompt.value = R.drawable.rest
                delay(5.seconds)
                val start = System.nanoTime()
                val restRms = samples.zip(sampleIntervals).map { rms(it.first, it.second) }
                println("rest " + (System.nanoTime() - start)/1e9 + " " + restRms.toString())

                samples.clear()
                sampleIntervals.clear()
                prompt.value = it
                delay(5.seconds)
                val start2 = System.nanoTime()
                val activeRms = samples.zip(sampleIntervals).map { rms(it.first, it.second) }
                println("active " + (System.nanoTime() - start2)/1e9 + " " + activeRms.toString())

                val temp = activeRms.zip(restRms).map {
                    10*log10(it.first / it.second)
                }
                temp + listOf(temp.max())
            }
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

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .crossfade(true)
                .build()
        }*/

        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        val workRequest = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val workInfo = WorkManager.getInstance(this).getWorkInfosForUniqueWork("sleeve-data-upload")
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("sleeve-data-upload",
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, workRequest)

        scope.launch {
            loop()
        }

        var value = mutableIntStateOf(0)
        var recording = mutableStateOf(false)
        var confirmClearState = mutableStateOf(false)
        val promptState = mutableStateOf<Int?>(null)
        val storage = Storage(this)
        //storage.add("Wave", node)
        storage.add("Intan", intanNode)
        storage.add("ICM", icmNode)
        storage.add("ADC", adcNode)

        node.start()
        val toggle = {
            node.toggle()
            //if(node.running) {
            //    storage.start()
            //} else {
           //     storage.stop()
            //}
        }

        enableEdgeToEdge()
        setContent {
            var count by remember { value }
            var error by remember { error }
            var status by remember { status }
            var calibrationStatus by remember { calibrationStatus }
            var recording by remember { recording }
            var confirmClear by remember { confirmClearState }
            var expanded by remember { mutableStateOf(false) }
            var selectPlot by remember { mutableStateOf("Intan") }
            var prompt by remember { promptState }
            var promptJob by remember {mutableStateOf<Job?>(null)}
            var snr by remember { snr }

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
                    Column(modifier = Modifier.fillMaxSize()) {
                        /*if(error != null) {
                            Text(error!!, modifier = Modifier.padding(innerPadding), color=Color.Red)
                        } else if (status != null) {
                            Text(status!!, modifier = Modifier.padding(innerPadding));
                        } else {*/

                        Text("Connection Status: $status", modifier = Modifier.padding(innerPadding))
                        Text("Calibration Status: $calibrationStatus", modifier = Modifier.padding(innerPadding))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = {
                                confirmClearState.value = true
                            }) { Text("Clear Data") }
                            Button(onClick = { shareData() }) { Text("Share Data") }
                        }

                        if (confirmClear) {
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

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Record")
                            Checkbox(
                                checked = recording,
                                onCheckedChange = {
                                    recording = it
                                    if (recording) {
                                        storage.start()
                                        promptJob = scope.launch {
                                            calibration(promptState)
                                        }
                                    } else {
                                        storage.stop()
                                        promptJob?.let { it.cancel() }
                                    }
                                }
                            )
                        }

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
                                /*DropdownMenuItem(
                                    text = { Text("Band Pass") },
                                    onClick = {
                                        expanded = false
                                        selectPlot = "Band Pass"
                                    }
                                )*/
                                /*DropdownMenuItem(
                                    text = { Text("Rectified") },
                                    onClick = {
                                        expanded = false
                                        selectPlot = "Rectified"
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Low Pass") },
                                    onClick = {
                                        expanded = false
                                        selectPlot = "Low Pass"
                                    }
                                )*/
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

                        //if(prompt != null) {
                            prompt?.let {Image(
                                painter = rememberAsyncImagePainter(it)
                                , "",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize())}
                        if(snr.isNotEmpty()) {
                            /*for (i in 0..<snr[0].size) {
                                Row() {
                                    snr.forEach {
                                        Text(String.format("%.2f", it[i]) + " ")
                                    }
                                }
                            }*/
                            Row() {
                                snr.forEach {
                                    Column() {
                                        it.forEach {
                                            Text(String.format("%.2f", it) + " ")
                                        }
                                    }
                                }
                            }
                            Button(onClick = {snr = listOf<List<Double>>()}) { Text("Clear SNR")}
                        }
                        //} else {
                            if(selectPlot == "Wave") {
                                Graph(modifier = Modifier.fillMaxSize(), node)
                            //} else if(selectPlot == "Band Pass") {
                            //    Graph(modifier = Modifier.fillMaxSize(), intanBandPassNode)
                            //} else if(selectPlot == "Rectified") {
                            //    Graph(modifier = Modifier.fillMaxSize(), rectifierNode)
                            //} else if(selectPlot == "Low Pass") {
                             //   Graph(modifier = Modifier.fillMaxSize(), lowPassNode)
                            } else if(selectPlot == "ICM") {
                                Graph(modifier = Modifier.fillMaxSize(), icmNode)
                            } else if(selectPlot == "Intan") {
                                Graph(modifier = Modifier.fillMaxSize(), intanNode)
                            } else if(selectPlot == "ADC") {
                                Graph(modifier = Modifier.fillMaxSize(), adcNode)
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


    Canvas(modifier=modifier.clipToBounds()) {
        invalidate.apply {}
        val count = signals.size
        val pixelSlice = size.height/count
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