package com.bit.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.NeuroVerseTheme
import com.bit.util.CrashReporter
import com.dark.system_encryptor.SystemEncryptor
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeuroVerseTheme {
                DiagnosticsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemEncryptor = remember { SystemEncryptor() }

    var crashLogsJson by remember { mutableStateOf("[]") }
    var policyLogsJson by remember { mutableStateOf("[]") }

    fun refreshLogs() {
        crashLogsJson = CrashReporter.getCrashReports(context)
        policyLogsJson = try {
            systemEncryptor.policyGetAuditLogs()
        } catch (e: Exception) {
            "[]"
        }
    }

    LaunchedEffect(Unit) {
        refreshLogs()
    }

    val systemInfo = remember {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        JSONObject().apply {
            put("device", Build.DEVICE)
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("cpuAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("cpuCores", runtime.availableProcessors())
            put("maxMemoryMb", maxMemory / (1024 * 1024))
            put("usedMemoryMb", usedMemory / (1024 * 1024))
            put("npu", getNpuName(context) ?: "Not Detected")
        }
    }

    fun exportDiagnostics() {
        try {
            val report = JSONObject().apply {
                put("system", systemInfo)
                put("crashes", JSONArray(crashLogsJson))
                put("policyEngineAudit", JSONArray(policyLogsJson))
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Diagnostics Report", report.toString(2))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Diagnostics exported to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Diagnostics & Crash Reports",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    ActionButton(
                        onClickListener = onBack,
                        icon = TnIcons.ArrowLeft,
                        contentDescription = "Back"
                    )
                },
                actions = {
                    IconButton(onClick = ::exportDiagnostics) {
                        Icon(TnIcons.Share, contentDescription = "Export JSON")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // --- Section 1: System Info ---
            item {
                Text(
                    text = "System Specs",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Standards.CardSmallCornerRadius),
                    color = Color(0x05FFFFFF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x0AFFFFFF))
                ) {
                    Column(
                        modifier = Modifier.padding(Standards.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecRow("Device Model", systemInfo.getString("model"))
                        SpecRow("Android OS", "Android ${systemInfo.getString("androidVersion")} (API ${systemInfo.getInt("sdkInt")})")
                        SpecRow("CPU Cores / ABI", "${systemInfo.getInt("cpuCores")} cores / ${systemInfo.getString("cpuAbi")}")
                        SpecRow("NPU / Neural Accelerator", systemInfo.getString("npu"))
                        SpecRow("Max Heap Size", "${systemInfo.getLong("maxMemoryMb")} MB")
                        SpecRow("Used Heap Size", "${systemInfo.getLong("usedMemoryMb")} MB")
                    }
                }
            }

            // --- Section 2: PolicyEngine Audit Logs ---
            item {
                Text(
                    text = "Security Audit Logs",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Standards.SpacingSm, bottom = 4.dp)
                )
                val policyArray = remember(policyLogsJson) {
                    try { JSONArray(policyLogsJson) } catch (e: Exception) { JSONArray() }
                }
                if (policyArray.length() == 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Standards.CardSmallCornerRadius),
                        color = Color(0x05FFFFFF)
                    ) {
                        Text(
                            text = "No policy authorization calls logged yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Standards.CardPadding)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0 until policyArray.length()) {
                            val log = policyArray.getJSONObject(i)
                            PolicyLogCard(log)
                        }
                    }
                }
            }

            // --- Process Isolation Test ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Standards.SpacingSm, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Process Isolation Test",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            try {
                                com.bit.worker.LlmModelWorker.simulateEngineCrash()
                                Toast.makeText(context, "Crash signal sent to engine process!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Simulate Native Crash", color = MaterialTheme.colorScheme.error)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Standards.CardSmallCornerRadius),
                    color = Color(0x05FFFFFF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x0AFFFFFF))
                ) {
                    Text(
                        text = "Test process isolation by simulating a native engine crash in the isolated :inference process. Tapping this kills the inference process immediately, allowing you to verify that the main application remains active and auto-recovers the loaded model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Standards.CardPadding)
                    )
                }
            }

            // --- Section 3: Crash Logs ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Standards.SpacingSm, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Crashes",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (crashLogsJson != "[]") {
                        TextButton(
                            onClick = {
                                CrashReporter.clearCrashReports(context)
                                refreshLogs()
                            }
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                val crashArray = remember(crashLogsJson) {
                    try { JSONArray(crashLogsJson) } catch (e: Exception) { JSONArray() }
                }
                if (crashArray.length() == 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Standards.CardSmallCornerRadius),
                        color = Color(0x05FFFFFF)
                    ) {
                        Text(
                            text = "No crash reports found. The application is running stable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Standards.CardPadding)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0 until crashArray.length()) {
                            val crash = crashArray.getJSONObject(i)
                            CrashCard(crash)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PolicyLogCard(log: JSONObject) {
    val allowed = log.optBoolean("allowed", false)
    val op = when (log.optInt("operation", -1)) {
        0 -> "READ_VAULT"
        1 -> "WRITE_VAULT"
        2 -> "LOAD_MODEL"
        3 -> "EXECUTE_TOOL"
        else -> "UNKNOWN"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0x05FFFFFF),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (allowed) Color(0x1F4CAF50) else Color(0x1FF44336)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(op, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    color = if (allowed) Color(0x194CAF50) else Color(0x19F44336),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (allowed) "ALLOWED" else "DENIED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (allowed) Color(0xFF4CAF50) else Color(0xFFF44336),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text("Caller: ${log.optString("packageName")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Reason: ${log.optString("reason")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CrashCard(crash: JSONObject) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0x05FFFFFF),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1FF44336))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = crash.optString("exception").substringAfterLast("."),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = crash.optString("timestamp"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Text(
                text = crash.optString("message"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(if (expanded) "Hide stacktrace" else "Show stacktrace", style = MaterialTheme.typography.labelSmall)
            }

            if (expanded) {
                Text(
                    text = crash.optString("stackTrace"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x0A000000), shape = RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

private fun getNpuName(context: Context): String? {
    val board = Build.BOARD.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val platform = try {
        val systemPropertiesClass = Class.forName("android.os.SystemProperties")
        val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
        (getMethod.invoke(null, "ro.board.platform") as String).lowercase()
    } catch (_: Exception) { "" }

    val isQcom = board.contains("qcom") || board.contains("msm") || board.contains("sm8") || board.contains("sm7") || board.contains("sm6") || hardware.contains("qcom") || platform.contains("qcom") || java.io.File("/dev/adsprpc-smd").exists()
    if (isQcom) return "Qualcomm Hexagon NPU"

    val isGoogle = board.contains("gs101") || board.contains("gs201") || board.contains("zuma") || board.contains("tensor") || hardware.contains("tensor") || java.io.File("/sys/class/gasket").exists() || java.io.File("/dev/gasket").exists()
    if (isGoogle) return "Google Tensor TPU"

    val isMtk = board.contains("mt6") || board.contains("mt8") || board.contains("mediatek") || platform.contains("mt") || java.io.File("/dev/apusys").exists()
    if (isMtk) return "MediaTek APU"

    val isSamsung = board.contains("exynos") || board.contains("s5e") || hardware.contains("exynos") || platform.contains("exynos")
    if (isSamsung) return "Samsung Eden NPU"

    val isHuawei = board.contains("kirin") || platform.contains("kirin") || java.io.File("/dev/npu_server").exists()
    if (isHuawei) return "Huawei DaVinci NPU"

    // Fallback to check NNAPI system feature
    if (context.packageManager.hasSystemFeature("android.hardware.neuralnetworks")) {
        return "Generic NNAPI Accelerator"
    }

    return null
}
