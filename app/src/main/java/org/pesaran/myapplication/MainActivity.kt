package org.pesaran.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        storage.add(node)

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
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.fillMaxSize()) {
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
            println("$lastX $lastY")
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