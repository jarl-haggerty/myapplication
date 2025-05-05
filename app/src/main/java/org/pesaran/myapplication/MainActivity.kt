package org.pesaran.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import org.pesaran.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    val node = WaveNode()
    private var bluetoothAllowed = mutableStateOf(false)
    private var bluetoothEnabled = mutableStateOf(false)
    private var bluetoothAdapterExists = mutableStateOf(false)
    private var error = mutableStateOf<String?>(null)

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            println("${result.device.name} ${result.device.address}" )
        }
    }


    @SuppressLint("MissingPermission")
    private fun scan() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter!!
        bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun findSleeve() {
        scan()
        /*val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter!!
        val pairs = bluetoothAdapter.bondedDevices!!
        pairs.find {
            it.name == "intan"
        }*/
    }

    val bluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode == RESULT_OK) {
            bluetoothEnabled.value = true
            findSleeve()
        } else {
            error.value = "Bluetooth disabled"
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(it.getOrDefault(android.Manifest.permission.BLUETOOTH_SCAN, false)
            && it.getOrDefault(android.Manifest.permission.BLUETOOTH_CONNECT, false)
            && it.getOrDefault(android.Manifest.permission.ACCESS_FINE_LOCATION, false)
            && it.getOrDefault(android.Manifest.permission.ACCESS_COARSE_LOCATION, false)) {
            bluetoothAllowed.value = true
            enableBluetooth()
        } else {
            error.value = "Bluetooth permissions denied"
        }
    }

    private fun enableBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter ?: return

        bluetoothAdapterExists.value = true

        if(bluetoothAdapter.isEnabled) {
            bluetoothEnabled.value = true
            findSleeve()
            return
        }

        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(enableIntent)
    }

    fun requestPermissions() {
        val permissions = listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val unpermitted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }
        if(android.Manifest.permission.BLUETOOTH_CONNECT !in unpermitted
            && android.Manifest.permission.BLUETOOTH_SCAN !in unpermitted
            && android.Manifest.permission.ACCESS_FINE_LOCATION !in unpermitted
            && android.Manifest.permission.ACCESS_COARSE_LOCATION !in unpermitted) {
            bluetoothAllowed.value = true
            enableBluetooth()
            return
        }
        if(unpermitted.isEmpty()) {
            return
        }

        permissionLauncher.launch(unpermitted.toTypedArray())
    }

    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter
        if(bluetoothAdapter != null) {
            bluetoothAdapterExists.value = true
            requestPermissions()
        } else {
            error.value = "No Bluetooth adapter found"
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val id = preferences.getString("id", "")
        if(id!!.isEmpty()) {
            preferences.edit().putString("id", UUID.randomUUID().toString()).commit()
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val work = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork("SleeveUpload", ExistingPeriodicWorkPolicy.KEEP, work)

        var value = mutableIntStateOf(0)

        val filesDir = getExternalFilesDir(null)

        node.ready.connect {
            val number = node.shorts(0)[0].toInt()
            value.intValue = number
        }

        val storage = Storage(this)
        storage.add("Wave", node)

        val toggle = {
            node.toggle()
            if(node.running) {
                storage.start()
            } else {
                storage.stop()
            }
        }

        enableEdgeToEdge()
        setContent {
            var count by remember { value }
            //var bluetoothAllowed by remember { bluetoothAllowed }
            var bluetoothEnabled by remember { bluetoothEnabled }
            //var bluetoothAdapterExists by remember { bluetoothAdapterExists }
            var error by remember { error }
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        if(error != null) {
                            Text(error!!, modifier = Modifier.padding(innerPadding));
                        } else if (bluetoothEnabled) {
                            Greeting(
                                name = "Android",
                                modifier = Modifier.padding(innerPadding)
                            );
                            Button(onClick = {toggle()}) { Text(count.toString())};
                            Graph(modifier=Modifier.fillMaxSize(), node)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Graph(modifier: Modifier = Modifier, node: GraphNode) {
    var invalidate by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(16)
            ++invalidate
        }
    }

    var time = 0.seconds
    var lastPathTime = (-10).seconds
    var currentPathTime = 0.seconds
    var lastPath = Path()
    var currentPath = Path()
    var lastX = 0.0f
    var lastY = 0.0f
    var rangeMin = Float.POSITIVE_INFINITY
    var rangeMax = Float.NEGATIVE_INFINITY

    node.ready.connect {
        val data = node.shorts(0)
        val interval = node.sampleInterval(0)

        for(d in data) {
            val x = time.inWholeNanoseconds.toFloat()/1e9f
            val y = d.toFloat()
            rangeMin = min(y, rangeMin)
            rangeMax = max(y, rangeMax)
            if(currentPath.isEmpty) {
                if(lastPath.isEmpty) {
                    currentPath.moveTo(x, y)
                } else {
                    currentPath.moveTo(lastX, lastY)
                    currentPath.lineTo(x, y)
                }
            } else {
                currentPath.lineTo(x, y)
            }
            lastX = x
            lastY = y
            //println("$lastX $lastY")
            time += interval
        }

        if (time - currentPathTime > 10.seconds) {
            lastPath = currentPath
            lastPathTime = currentPathTime
            currentPath = Path()
            currentPathTime = time
        }
    }

    Canvas(modifier=modifier.clipToBounds()) {
        invalidate.apply {}
        val canvasWidth = size.width
        val canvasHeight = size.height
        if(!rangeMax.isInfinite()) {
            drawRect(Color.Black)
            val scale = canvasWidth/10
            val scaleY = canvasHeight/(rangeMax-rangeMin)
            //val bottom = rangeMin*scaleY
            translate(0f, canvasHeight+rangeMin*scaleY) {
                scale(scaleX = scale, scaleY = -scaleY, pivot = Offset(0f,0f)) {
                    translate(9.9f-time.inWholeMilliseconds/1e3f, 0f) {
                        drawPath(lastPath, Color.Blue, style=Stroke(width=10f/scale))
                    }
                    translate(9.9f-time.inWholeMilliseconds/1e3f, 0f) {
                        drawPath(currentPath, Color.Blue, style=Stroke(width=10f/scale))
                    }
                }
            }
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