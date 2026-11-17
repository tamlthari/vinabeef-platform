package com.vinabeef.field

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: OfflineFieldDbHelper
    private lateinit var rfidScanner: RfidScannerManager
    private var nfcAdapter: NfcAdapter? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var activeAnimal: CachedAnimal? = null
    private var jwtToken: String = ""

    private lateinit var tvSyncStatus: TextView
    private lateinit var tvReaderStatus: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etBarnId: EditText
    private lateinit var etTagInput: EditText
    private lateinit var tvAnimalCard: TextView
    private lateinit var spRecordType: Spinner
    private lateinit var spHealthStatus: Spinner
    private lateinit var etTreatmentTitle: EditText
    private lateinit var etMedicationBatch: EditText
    private lateinit var etClinicalNotes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = OfflineFieldDbHelper(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)
        etServerUrl = findViewById(R.id.etServerUrl)
        etBarnId = findViewById(R.id.etBarnId)
        etTagInput = findViewById(R.id.etTagInput)
        tvAnimalCard = findViewById(R.id.tvAnimalCard)
        spRecordType = findViewById(R.id.spRecordType)
        spHealthStatus = findViewById(R.id.spHealthStatus)
        etTreatmentTitle = findViewById(R.id.etTreatmentTitle)
        etMedicationBatch = findViewById(R.id.etMedicationBatch)
        etClinicalNotes = findViewById(R.id.etClinicalNotes)

        spRecordType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("VACCINATION", "TREATMENT", "HEALTH_CHECK", "QUARANTINE")
        )
        spHealthStatus.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("HEALTHY", "UNDER_TREATMENT", "QUARANTINE")
        )

        rfidScanner = RfidScannerManager(
            context = this,
            onStatusChanged = { msg -> runOnUiThread { tvReaderStatus.text = "RFID Reader: $msg" } },
            onTagScanned = { scannedCode -> runOnUiThread { onRfidTagDetected(scannedCode) } }
        )

        findViewById<Button>(R.id.btnDownloadBundle).setOnClickListener {
            downloadBarnCacheBundle()
        }

        findViewById<Button>(R.id.btnConnectBle).setOnClickListener {
            requestBlePermissionsAndScan()
        }

        findViewById<Button>(R.id.btnSimulateScan).setOnClickListener {
            val sample = dbHelper.getRandomCachedAnimal()
            if (sample != null) {
                onRfidTagDetected(sample.tagEpc.ifEmpty { sample.animalId })
            } else {
                onRfidTagDetected("E28011700000020000000001")
            }
        }

        findViewById<Button>(R.id.btnLookupTag).setOnClickListener {
            onRfidTagDetected(etTagInput.text.toString())
        }

        findViewById<Button>(R.id.btnSaveOffline).setOnClickListener {
            saveClinicalRecordLocallyAndSync()
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            flushOfflineOutboxToServer()
        }

        refreshStatusBanner()
    }

    private fun onRfidTagDetected(code: String) {
        val clean = code.trim().uppercase()
        etTagInput.setText(clean)
        val animal = dbHelper.findAnimalByTagOrId(clean)
        activeAnimal = animal

        if (animal != null) {
            tvAnimalCard.text = """
                ANIMAL ID: ${animal.animalId}  |  STATUS: ${animal.healthStatus}
                RFID EPC:  ${animal.tagEpc}
                BARN:      ${animal.barnCode} (${animal.barnId})
                BREED:     ${animal.breed}  |  WEIGHT: ${animal.weightKg} kg
            """.trimIndent()
        } else {
            tvAnimalCard.text = "Scanned Tag [$clean] — Not in local barn cache (will still queue with Tag EPC for server resolution)."
        }
    }

    private fun saveClinicalRecordLocallyAndSync() {
        val tagOrId = etTagInput.text.toString().trim()
        if (tagOrId.isEmpty() && activeAnimal == null) {
            Toast.makeText(this, "Please scan an RFID tag first.", Toast.LENGTH_SHORT).show()
            return
        }

        val clientEventId = "MOB-" + UUID.randomUUID().toString()
        val nowIso = Instant.now().toString()
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-HANDHELD"

        val payload = JSONObject().apply {
            put("clientEventId", clientEventId)
            put("animalId", activeAnimal?.animalId ?: if (tagOrId.startsWith("VB-") || tagOrId.startsWith("COW-")) tagOrId else "")
            put("tagEpc", activeAnimal?.tagEpc ?: tagOrId)
            put("recordType", spRecordType.selectedItem.toString())
            put("healthStatus", spHealthStatus.selectedItem.toString())
            put("title", etTreatmentTitle.text.toString().trim())
            put("medication", etMedicationBatch.text.toString().trim())
            put("notes", etClinicalNotes.text.toString().trim())
            put("recordedAt", nowIso)
            put("deviceId", deviceId)
        }

        dbHelper.enqueueHealthRecord(clientEventId, payload, nowIso)
        refreshStatusBanner()
        Toast.makeText(this, "Saved to offline SQLite outbox!", Toast.LENGTH_SHORT).show()

        // Attempt background sync immediately; if offline in barn, stays safely queued
        flushOfflineOutboxToServer()
    }

    private fun ensureAuthenticated(baseUrl: String): String {
        if (jwtToken.isNotEmpty()) return jwtToken
        val url = URL("$baseUrl/auth/login")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val creds = """{"username":"supervisor_barn1","password":"password123"}"""
        conn.outputStream.use { it.write(creds.toByteArray(Charsets.UTF_8)) }

        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().readText()
            jwtToken = JSONObject(body).optString("token", "")
        }
        return jwtToken
    }

    private fun downloadBarnCacheBundle() {
        val baseUrl = etServerUrl.text.toString().trim().removeSuffix("/")
        val barnId = etBarnId.text.toString().trim()
        tvReaderStatus.text = "RFID Reader: Downloading barn cache ($barnId)..."

        executor.execute {
            try {
                val token = ensureAuthenticated(baseUrl)
                val url = URL("$baseUrl/mobile/sync-bundle?barnId=$barnId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val cattleArr = json.optJSONArray("cattle") ?: JSONArray()
                    dbHelper.replaceCachedCattle(cattleArr)
                    runOnUiThread {
                        refreshStatusBanner()
                        tvReaderStatus.text = "RFID Reader: Cached ${cattleArr.length()} cattle for offline scanning."
                        Toast.makeText(this, "Downloaded ${cattleArr.length()} cattle to phone!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        tvReaderStatus.text = "RFID Reader: Sync failed (HTTP ${conn.responseCode})"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvReaderStatus.text = "RFID Reader: Offline mode active (${e.message})"
                }
            }
        }
    }

    private fun flushOfflineOutboxToServer() {
        val pending: JSONArray = dbHelper.getPendingOutbox()
        if (pending.length() == 0) return

        val baseUrl = etServerUrl.text.toString().trim().removeSuffix("/")
        executor.execute {
            try {
                val token = ensureAuthenticated(baseUrl)
                val url = URL("$baseUrl/mobile/health-sync")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(pending.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                    val ackIds = resp.optJSONArray("acknowledgedClientIds") ?: JSONArray()
                    dbHelper.deleteAcknowledgedIds(ackIds)
                    runOnUiThread {
                        refreshStatusBanner()
                        Toast.makeText(this, "Synced ${ackIds.length()} clinical records to cloud!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { refreshStatusBanner() }
            }
        }
    }

    private fun refreshStatusBanner() {
        val cached = dbHelper.getCachedCount()
        val pending = dbHelper.getPendingCount()
        tvSyncStatus.text = "Offline Herd Cache: $cached cattle  |  Pending Outbox: $pending"
    }

    private fun requestBlePermissionsAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            rfidScanner.startBleReaderDiscovery()
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag != null) {
            rfidScanner.handleNfcTag(tag)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidScanner.release()
    }
}