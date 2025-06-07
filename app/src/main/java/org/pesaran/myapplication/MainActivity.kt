package org.pesaran.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import org.pesaran.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import java.util.LinkedList

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
    private var status = mutableStateOf<String?>(null)

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
            bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)

            val device = scanChannel.receive()

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
                                //4 -> intanNode.process(it)
                                //5 -> adcNode.process(it)
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
                            wroteChannel.send(Status(true, ""))
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
                            wroteDescriptorChannel.send(Status(true, ""))
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

            val write: (suspend (Block) -> Status) = {
                val data = it.encode()
                println(gatt)
                println(rxCharacteristic)
                println(data)
                gatt!!.writeCharacteristic(rxCharacteristic!!, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                wroteChannel.receive()
            }

            //gatt.setCharacteristicNotification(txCharacteristic, false)
            gatt.setCharacteristicNotification(txCharacteristic, true)
            txCharacteristic.descriptors.forEach {
                gatt.writeDescriptor(it,  BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                wroteDescriptorChannel.receive()
            }
            //gatt.writeCharacteristic(txCharacteristic!!, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            //delay(1000)
            println(1)
            write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(2, 0, 0, 0, 63))))
            //delay(1000)
            println(2)
            write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(4, 0, 0, -1, -1))))
            //delay(1000)
            println(3)
            write(Block(BlockType.CMD_BLOCK, ID_SET_CHANNEL_MASK, ByteBuffer.wrap(byteArrayOf(5, 0, 0, 0, 1))))
            //delay(1000)
            println(4)

            write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(2, 0))))
            println(5)
            write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(4, 19))))
            println(6)
            write(Block(BlockType.CMD_BLOCK, ID_SET_SAMPLE_RATE, ByteBuffer.wrap(byteArrayOf(5, 0))))
            println(7)

            write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(2, 1))))
            println(1)
            write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(4, 1))))
            println(1)
            write(Block(BlockType.CMD_BLOCK, ID_ENABLE, ByteBuffer.wrap(byteArrayOf(5, 1))))
            println(66666)
            gatt.readCharacteristic(txCharacteristic)

            status.value = "Connected"
            while(connectionState == BluetoothProfile.STATE_CONNECTED) {
                //status.value = "Ready $connectionState"
                println(connectionState)
                connectionState = connectionChannel.receive()
            }
            status.value = "Disconnected"
        }
    }

    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scope.launch {
            loop()
        }

        /*scope.launch {
            var i = 0
            while(true) {
                delay(1000)
                status.value = if(ready) {"ready $i"} else {"waiting $i"}
                ++i
            }
        }*/

        var value = mutableIntStateOf(0)
        //val storage = Storage(this)
        //storage.add("Wave", node)

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
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        /*if(error != null) {
                            Text(error!!, modifier = Modifier.padding(innerPadding), color=Color.Red)
                        } else if (status != null) {
                            Text(status!!, modifier = Modifier.padding(innerPadding));
                        } else {*/
                            Text(status!!, modifier = Modifier.padding(innerPadding));
                            Button(onClick = {toggle()}) { Text(count.toString())};
                            Graph(modifier=Modifier.fillMaxSize(), icmNode)
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
fun Graph(modifier: Modifier = Modifier, node: ICM20948Node) {
    var invalidate by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(16)
            ++invalidate
        }
    }

    val signals = mutableListOf<SignalPlot>()

    node.ready.connect {
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