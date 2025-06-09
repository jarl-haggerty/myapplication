package org.pesaran.myapplication

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
import androidx.compose.runtime.mutableStateListOf
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
import org.pesaran.myapplication.ui.theme.MyApplicationTheme
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    val adcNode = ADCNode()
    val icmNode = ICM20948Node()
    private var error = mutableStateOf<String?>(null)
    private var status = mutableStateOf<String>("")

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
                    println("${result.device.name} ${result.device.address} ${callbackType}")
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

    suspend fun calibration(prompt: MutableState<Int?>) {
        try {
            prompt.value = R.drawable.calibration_1
            delay(5.seconds)
            prompt.value = R.drawable.calibration_2
            delay(5.seconds)
            prompt.value = R.drawable.calibration_3
            delay(5.seconds)
            prompt.value = R.drawable.calibration_4
            delay(5.seconds)
            prompt.value = R.drawable.calibration_5
            delay(5.seconds)
            prompt.value = R.drawable.calibration_6
            delay(5.seconds)
            prompt.value = R.drawable.calibration_7
            delay(5.seconds)
        } finally {
            prompt.value = null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        val workRequest = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork("sleeve-data-upload",
            ExistingPeriodicWorkPolicy.UPDATE, workRequest)

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
            var recording by remember { recording }
            var confirmClear by remember { confirmClearState }
            var expanded by remember { mutableStateOf(false) }
            var selectPlot by remember { mutableStateOf("ICM") }
            var prompt by remember { promptState }
            var promptJob by remember {mutableStateOf<Job?>(null)}
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        /*if(error != null) {
                            Text(error!!, modifier = Modifier.padding(innerPadding), color=Color.Red)
                        } else if (status != null) {
                            Text(status!!, modifier = Modifier.padding(innerPadding));
                        } else {*/

                        Text("Status: $status", modifier = Modifier.padding(innerPadding))
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
                                painter = painterResource(id = it), "",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize())}
                        //} else {
                            if(selectPlot == "ICM") {
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
    LaunchedEffect(Unit) {
        val connection = node.ready.connect {
            if(node.hasAnalogData()) {
                (0..<node.numChannels()).forEach {
                    while(signals.size <= it) {
                        signals.add(SignalPlot())
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
            it.draw(this, Rect(0F, i*pixelSlice, size.width, (i+1)*pixelSlice), COLORS[i % COLORS.size])
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
    MyApplicationTheme {
        Greeting("Android")
    }
}