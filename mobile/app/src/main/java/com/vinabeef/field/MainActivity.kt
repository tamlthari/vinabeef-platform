package com.vinabeef.field

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-site vaccination and health recording. Every record is written to the phone's SQLite outbox first and
 * is only removed when the server says it stored it, so a lost connection in the barn never loses a record.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var db: OfflineFieldDbHelper
    private lateinit var session: SessionStore
    private lateinit var rfidScanner: RfidScannerManager
    private var nfcAdapter: NfcAdapter? = null

    // One thread for all network and database writes, so a send never overlaps a barn download.
    private val worker = Executors.newSingleThreadExecutor()
    private val syncRunning = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val debouncer = ScanDebouncer()

    private var activeAnimal: CachedAnimal? = null
    private var activeScanMethod = "MANUAL"
    private var activeScannedAt: String? = null
    private var protocols: List<Protocol> = emptyList()
    private var shownProtocols: List<Protocol> = emptyList()
    private var autoFilledTitle: String? = null
    private var updatingForm = false
    private var syncNote = ""

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val retryLoop = object : Runnable {
        override fun run() {
            syncPending(false)
            uiHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    private lateinit var tvSyncStatus: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvReaderStatus: TextView
    private lateinit var btnReviewRejected: Button
    private lateinit var llSignIn: LinearLayout
    private lateinit var llSession: LinearLayout
    private lateinit var etServerUrl: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSignIn: Button
    private lateinit var etBarnId: EditText
    private lateinit var etTagInput: EditText
    private lateinit var tvAnimalCard: TextView
    private lateinit var tvHistory: TextView
    private lateinit var spRecordType: Spinner
    private lateinit var spProtocol: Spinner
    private lateinit var etTreatmentTitle: EditText
    private lateinit var etMedicationBatch: EditText
    private lateinit var etDoseMl: EditText
    private lateinit var spRoute: Spinner
    private lateinit var tvNextDue: TextView
    private lateinit var spHealthStatus: Spinner
    private lateinit var etClinicalNotes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = OfflineFieldDbHelper(this)
        session = SessionStore(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvSession = findViewById(R.id.tvSession)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)
        btnReviewRejected = findViewById(R.id.btnReviewRejected)
        llSignIn = findViewById(R.id.llSignIn)
        llSession = findViewById(R.id.llSession)
        etServerUrl = findViewById(R.id.etServerUrl)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnSignIn = findViewById(R.id.btnSignIn)
        etBarnId = findViewById(R.id.etBarnId)
        etTagInput = findViewById(R.id.etTagInput)
        tvAnimalCard = findViewById(R.id.tvAnimalCard)
        tvHistory = findViewById(R.id.tvHistory)
        spRecordType = findViewById(R.id.spRecordType)
        spProtocol = findViewById(R.id.spProtocol)
        etTreatmentTitle = findViewById(R.id.etTreatmentTitle)
        etMedicationBatch = findViewById(R.id.etMedicationBatch)
        etDoseMl = findViewById(R.id.etDoseMl)
        spRoute = findViewById(R.id.spRoute)
        tvNextDue = findViewById(R.id.tvNextDue)
        spHealthStatus = findViewById(R.id.spHealthStatus)
        etClinicalNotes = findViewById(R.id.etClinicalNotes)

        etServerUrl.setText(session.serverUrl)
        etUsername.setText(session.username)
        etBarnId.setText(session.barnId)

        spRecordType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, RECORD_TYPES)
        spHealthStatus.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, HEALTH_STATUSES)
        spRoute.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            FieldRules.ROUTES.map { if (it.isEmpty()) "Route: not recorded" else it }
        )
        spRecordType.onItemSelectedListener = selectionListener {
            if (!updatingForm) {
                refreshProtocolSpinner()
                applyRecordTypeRules()
                updateNextDueAndHistory()
            }
        }
        spProtocol.onItemSelectedListener = selectionListener {
            if (!updatingForm) applyProtocol()
        }

        rfidScanner = RfidScannerManager(
            context = this,
            onStatusChanged = { msg -> runOnUiThread { setReaderStatus(msg) } },
            onTagScanned = { code, method -> runOnUiThread { onTagDetected(code, method) } }
        )

        findViewById<Button>(R.id.btnSignIn).setOnClickListener { signIn() }
        findViewById<Button>(R.id.btnSignOut).setOnClickListener { signOut() }
        findViewById<Button>(R.id.btnDownloadBundle).setOnClickListener { loadBarn() }
        findViewById<Button>(R.id.btnConnectBle).setOnClickListener { requestBlePermissionsAndScan() }
        findViewById<Button>(R.id.btnLookupTag).setOnClickListener { onTagDetected(etTagInput.text.toString(), "MANUAL") }
        findViewById<Button>(R.id.btnSaveOffline).setOnClickListener { onSaveClicked() }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener { syncPending(true) }
        btnReviewRejected.setOnClickListener { showRejected() }

        // The demo scan picks a random cached animal. It exists for the emulator and is not shown in release builds.
        val demo = findViewById<Button>(R.id.btnSimulateScan)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        demo.visibility = if (debuggable) View.VISIBLE else View.GONE
        demo.setOnClickListener {
            val sample = db.getRandomCachedAnimal()
            if (sample == null) {
                toast("Load a barn first: there are no cattle on the phone.")
            } else {
                onTagDetected(sample.tagEpc.ifEmpty { sample.animalId }, "DEMO")
            }
        }

        protocols = db.getProtocols()
        refreshProtocolSpinner()
        applyRecordTypeRules()
        refreshStatus()
    }

    // ------------------------------------------------------------------ form behaviour

    private fun selectionListener(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            block()
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun selectedRecordType(): String =
        RECORD_TYPES[spRecordType.selectedItemPosition.coerceAtLeast(0)]

    /** Position 0 is "choose", the last position is "other", the ones in between are the farm's protocols. */
    private fun selectedProtocol(): Protocol? {
        val pos = spProtocol.selectedItemPosition
        return if (pos in 1..shownProtocols.size) shownProtocols[pos - 1] else null
    }

    private fun refreshProtocolSpinner() {
        val type = selectedRecordType()
        shownProtocols = protocols.filter { it.recordType == type }
        val labels = listOf(NONE_LABEL) + shownProtocols.map { it.title } + OTHER_LABEL
        updatingForm = true
        spProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spProtocol.setSelection(0)
        updatingForm = false
        if (etTreatmentTitle.text.toString() == autoFilledTitle) {
            etTreatmentTitle.setText("")
            autoFilledTitle = null
        }
    }

    private fun applyProtocol() {
        val p = selectedProtocol()
        if (p != null) {
            etTreatmentTitle.setText(p.title)
            autoFilledTitle = p.title
            val dose = p.defaultDoseMl
            if (dose != null) etDoseMl.setText(formatDose(dose))
            val routeIndex = FieldRules.ROUTES.indexOf(p.route ?: "")
            if (routeIndex >= 0) spRoute.setSelection(routeIndex)
            if (selectedRecordType() != "VACCINATION") selectStatus(p.resultingStatus)
        } else if (etTreatmentTitle.text.toString() == autoFilledTitle) {
            etTreatmentTitle.setText("")
            autoFilledTitle = null
        }
        updateNextDueAndHistory()
    }

    /** A vaccination does not change how the animal is, and a quarantine record is always a quarantine. */
    private fun applyRecordTypeRules() {
        when (selectedRecordType()) {
            "VACCINATION" -> {
                selectStatus("HEALTHY")
                spHealthStatus.isEnabled = false
                etMedicationBatch.hint = "Batch / lot number (read it from the vial)"
            }
            "QUARANTINE" -> {
                selectStatus("QUARANTINE")
                spHealthStatus.isEnabled = false
                etMedicationBatch.hint = "Medication / batch (optional)"
            }
            "TREATMENT" -> {
                selectStatus("UNDER_TREATMENT")
                spHealthStatus.isEnabled = true
                etMedicationBatch.hint = "Medication / batch (optional)"
            }
            else -> {
                selectStatus("HEALTHY")
                spHealthStatus.isEnabled = true
                etMedicationBatch.hint = "Medication / batch (optional)"
            }
        }
    }

    private fun selectStatus(status: String) {
        spHealthStatus.setSelection(HEALTH_STATUSES.indexOf(status).coerceAtLeast(0))
    }

    private fun updateNextDueAndHistory() {
        val type = selectedRecordType()
        val now = Instant.now()
        val due = if (type == "VACCINATION") FieldRules.nextDueDate(now, selectedProtocol()?.intervalDays) else null
        tvNextDue.text = if (due != null) "Booster due: $due" else ""

        val animal = activeAnimal
        val title = etTreatmentTitle.text.toString().trim()
        tvHistory.text = if (animal != null && title.isNotEmpty() && type == "VACCINATION") {
            FieldRules.historyLine(title, db.lastVaccination(animal.animalId, title), now)
        } else {
            ""
        }
    }

    private fun readForm() = RecordForm(
        recordType = selectedRecordType(),
        healthStatus = HEALTH_STATUSES[spHealthStatus.selectedItemPosition.coerceAtLeast(0)],
        title = etTreatmentTitle.text.toString(),
        batch = etMedicationBatch.text.toString(),
        doseText = etDoseMl.text.toString(),
        route = FieldRules.ROUTES[spRoute.selectedItemPosition.coerceAtLeast(0)],
        notes = etClinicalNotes.text.toString()
    )

    private fun formatDose(d: Double): String = if (d == Math.floor(d)) d.toInt().toString() else d.toString()

    // ------------------------------------------------------------------ scanning

    private fun onTagDetected(code: String, method: String) {
        val clean = TagCodes.normalise(code)
        if (clean.isEmpty()) return
        // A wand that keeps reading the same tag while the trigger is held is still one scan.
        if (method != "MANUAL" && !debouncer.accept(clean, SystemClock.elapsedRealtime())) return

        etTagInput.setText(clean)
        activeScanMethod = method
        activeScannedAt = Instant.now().toString()
        val animal = db.findAnimalByTagOrId(clean)
        activeAnimal = animal

        tvAnimalCard.text = if (animal != null) {
            val flag = if (animal.healthStatus != "HEALTHY") "\nNOTE: this animal is ${animal.healthStatus}." else ""
            "ANIMAL: ${animal.animalId}\nTAG: ${animal.tagEpc}\nBARN: ${animal.barnCode} (${animal.barnId})\n" +
                    "BREED: ${animal.breed} | WEIGHT: ${animal.weightKg} kg | STATUS: ${animal.healthStatus}$flag"
        } else {
            "Tag $clean is not in the herd loaded on this phone. You can still save: the server checks the tag " +
                    "when it receives the record and tells you here if it does not know it."
        }
        updateNextDueAndHistory()
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
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLE)
        } else {
            rfidScanner.startBleReaderDiscovery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            rfidScanner.startBleReaderDiscovery()
        } else {
            setReaderStatus("Bluetooth permission was not granted. The phone NFC reader still works.")
        }
    }

    // ------------------------------------------------------------------ saving

    private fun onSaveClicked() {
        val typed = etTagInput.text.toString().trim()
        val animal = activeAnimal
        if (animal == null && typed.isEmpty()) {
            toast("Scan or type the animal's tag first.")
            return
        }
        val form = readForm()
        val problem = FieldRules.validate(form)
        if (problem != null) {
            toast(problem, true)
            return
        }
        if (form.recordType == "VACCINATION" && animal != null) {
            val now = Instant.now()
            val last = db.lastVaccination(animal.animalId, form.title)
            if (FieldRules.withinDuplicateWindow(last, now)) {
                AlertDialog.Builder(this)
                    .setTitle("Already vaccinated")
                    .setMessage(FieldRules.historyLine(form.title.trim(), last, now) + "\n\nRecord it again?")
                    .setPositiveButton("Record again") { _, _ -> storeRecord(form, animal, typed) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        storeRecord(form, animal, typed)
    }

    private fun storeRecord(form: RecordForm, animal: CachedAnimal?, typed: String) {
        val now = Instant.now()
        val protocol = selectedProtocol()
        val clientEventId = "MOB-" + UUID.randomUUID().toString()
        val tag = animal?.tagEpc?.takeIf { it.isNotEmpty() } ?: TagCodes.normalise(typed)
        // For an animal the phone does not know: a typed animal id goes to the server as an id, a tag code as a tag.
        val animalId = animal?.animalId ?: if (TagCodes.looksLikeTag(typed)) "" else typed.trim()
        val due = if (form.recordType == "VACCINATION") FieldRules.nextDueDate(now, protocol?.intervalDays) else null
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-HANDHELD"

        val fields = FieldRules.buildPayload(
            clientEventId, animalId, tag, form, protocol?.protocolId ?: "", due,
            activeScanMethod, activeScannedAt, now.toString(), deviceId, APP_VERSION, session.username
        )
        val json = JSONObject()
        for ((key, value) in fields) {
            if (value != null) json.put(key, value)
        }
        val label = (if (animalId.isNotEmpty()) animalId else tag) + " - " + form.title.trim()
        db.enqueueHealthRecord(json, label)

        // Ready for the next animal: keep the vaccine, batch, dose and route, clear what belongs to this animal.
        activeAnimal = null
        activeScanMethod = "MANUAL"
        activeScannedAt = null
        etTagInput.setText("")
        etClinicalNotes.setText("")
        tvAnimalCard.text = "Saved. Scan the next animal."
        tvHistory.text = ""
        refreshStatus()
        toast("Saved on this phone: $label. Waiting to send: ${db.getPendingCount()}.")
        syncPending(false)
    }

    // ------------------------------------------------------------------ sign-in and barn download

    private fun signIn() {
        val url = etServerUrl.text.toString().trim().removeSuffix("/")
        val user = etUsername.text.toString().trim()
        val pass = etPassword.text.toString()
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            toast("Enter the server address, username and password.")
            return
        }
        // Release builds do not allow plain HTTP; say so instead of a vague "cannot reach the server".
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable && !url.startsWith("https://", ignoreCase = true)) {
            toast("This app connects only over HTTPS. Enter the address as https://...", true)
            return
        }
        btnSignIn.isEnabled = false
        worker.execute {
            try {
                val resp = ApiClient.login(url, user, pass)
                if (resp.code == 200) {
                    val json = JSONObject(resp.body)
                    val token = json.optString("token", "")
                    if (token.isEmpty()) {
                        runOnUiThread { toast("The server did not return a sign-in token.", true) }
                    } else {
                        session.serverUrl = url
                        // The server's spelling of the name is what the records are matched against.
                        session.username = json.optString("username", "").ifBlank { user }
                        session.token = token
                        val assigned = json.optJSONArray("assignedBarns")
                        if (session.barnId.isEmpty() && assigned != null && assigned.length() > 0) {
                            session.barnId = assigned.getString(0)
                        }
                        runOnUiThread {
                            etPassword.setText("")
                            etBarnId.setText(session.barnId)
                            syncNote = ""
                            updateSessionViews()
                            loadBarn()
                            syncPending(false)
                        }
                    }
                } else if (resp.code == 401) {
                    runOnUiThread { toast("Wrong username or password.", true) }
                } else {
                    runOnUiThread { toast("Sign-in failed (HTTP ${resp.code}).", true) }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Cannot reach the server at $url. Check the address and the connection.", true) }
            } finally {
                runOnUiThread { btnSignIn.isEnabled = true }
            }
        }
    }

    private fun signOut() {
        session.signOut()
        syncNote = ""
        updateSessionViews()
        refreshStatus()
        toast("Signed out. Records that are waiting stay on the phone.")
    }

    private fun updateSessionViews() {
        val signedIn = session.token.isNotEmpty()
        llSignIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        llSession.visibility = if (signedIn) View.VISIBLE else View.GONE
        tvSession.text = if (signedIn) {
            "Signed in as ${session.username} | Barn: ${session.barnId.ifEmpty { "your barn" }}"
        } else {
            "Not signed in - records can still be saved and are sent after you sign in"
        }
    }

    private fun loadBarn() {
        if (session.token.isEmpty()) {
            toast("Sign in first.")
            return
        }
        val baseUrl = session.serverUrl
        val token = session.token
        val requested = etBarnId.text.toString().trim()
        syncNote = "Loading the barn..."
        refreshStatus()
        worker.execute {
            var note: String
            try {
                val resp = ApiClient.downloadBundle(baseUrl, token, requested)
                if (resp.code == 401 || resp.code == 403) {
                    note = expireSession(token)
                } else if (resp.code !in 200..299) {
                    note = "Could not load the barn (HTTP ${resp.code})."
                } else {
                    val json = JSONObject(resp.body)
                    val cattle = json.optJSONArray("cattle") ?: JSONArray()
                    val selected = json.optString("selectedBarnId", requested)
                    if (cattle.length() == 0) {
                        note = "The server returned no cattle for '$selected'. The herd on the phone was not changed."
                    } else {
                        db.replaceCachedCattle(cattle)
                        db.replaceProtocols(json.optJSONArray("protocols") ?: JSONArray())
                        db.replaceVaccinationHistory(json.optJSONArray("recentVaccinations") ?: JSONArray())
                        session.barnId = if (requested.equals("ALL", ignoreCase = true)) "ALL" else selected
                        val total = json.optInt("cattleTotal", cattle.length())
                        note = if (json.optBoolean("cattleTruncated", false)) {
                            "Loaded the first ${cattle.length()} of $total cattle. Pick a single barn to get them all."
                        } else {
                            "Loaded ${cattle.length()} cattle for offline use."
                        }
                    }
                }
            } catch (e: Exception) {
                note = "Cannot reach the server. The herd already on the phone is still usable."
            }
            runOnUiThread {
                syncNote = note
                protocols = db.getProtocols()
                refreshProtocolSpinner()
                etBarnId.setText(session.barnId)
                updateSessionViews()
                refreshStatus()
            }
        }
    }

    /** The token is no longer accepted. Unsent records stay; they go out after the next sign-in. */
    private fun expireSession(usedToken: String): String {
        if (session.token == usedToken) session.signOut()
        runOnUiThread { updateSessionViews() }
        return "Your session ended. Sign in again to send the waiting records."
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends what is waiting, oldest first. A record leaves the phone only when the server lists its id in
     * `acknowledgedClientIds`; one the server refuses is kept as REJECTED with the reason; a failed request
     * changes nothing, so everything is simply tried again later.
     */
    private fun syncPending(userInitiated: Boolean) {
        if (session.token.isEmpty()) {
            if (userInitiated) toast("Sign in to send the waiting records.")
            return
        }
        val user = session.username
        val ofOthers = db.getPendingOfOthersCount(user)
        if (db.getPendingCount() - ofOthers == 0) {
            if (userInitiated) {
                toast(
                    if (ofOthers == 0) "Nothing is waiting to be sent."
                    else "The $ofOthers waiting record(s) were saved by another user. They are sent when that user signs in.",
                    ofOthers > 0
                )
            }
            return
        }
        if (!syncRunning.compareAndSet(false, true)) return
        val baseUrl = session.serverUrl
        val token = session.token
        worker.execute {
            var sent = 0
            var refused = 0
            var note = ""
            try {
                while (true) {
                    val batch = db.getPendingOutbox(user, BATCH_SIZE)
                    if (batch.length() == 0) break
                    val resp = ApiClient.syncRecords(baseUrl, token, batch)
                    if (resp.code == 401 || resp.code == 403) {
                        note = expireSession(token)
                        break
                    }
                    if (resp.code !in 200..299) {
                        note = "The server answered HTTP ${resp.code}. The records stay on the phone and are tried again."
                        break
                    }
                    val json = JSONObject(resp.body)
                    val acknowledged = json.optJSONArray("acknowledgedClientIds") ?: JSONArray()
                    val rejected = json.optJSONArray("rejected") ?: JSONArray()
                    db.deleteAcknowledgedIds(acknowledged)
                    for (i in 0 until rejected.length()) {
                        val r = rejected.getJSONObject(i)
                        val id = r.optString("clientEventId")
                        if (id.isNotEmpty()) {
                            db.markRejected(id, FieldRules.friendlyReason(r.optString("code"), r.optString("message")))
                        }
                    }
                    sent += acknowledged.length()
                    refused += rejected.length()
                    if (acknowledged.length() + rejected.length() == 0) break
                }
            } catch (e: Exception) {
                note = "No connection to the server. The records stay on the phone and are sent when it returns."
            } finally {
                syncRunning.set(false)
            }
            runOnUiThread {
                syncNote = note
                refreshStatus()
                if (sent > 0) toast("Sent $sent record(s) to the server.")
                if (refused > 0) toast("$refused record(s) were not accepted. Tap the orange button to see why.", true)
                if (userInitiated && sent == 0 && refused == 0 && note.isNotEmpty()) toast(note, true)
            }
        }
    }

    private fun showRejected() {
        val items = db.getRejected(session.username)
        if (items.isEmpty()) {
            refreshStatus()
            return
        }
        val text = items.joinToString("\n\n") { it.label + "\n" + it.reason }
        AlertDialog.Builder(this)
            .setTitle("Records the server did not accept")
            .setMessage(text)
            .setPositiveButton("Try again") { _, _ ->
                db.retryRejected(session.username)
                refreshStatus()
                syncPending(true)
            }
            .setNeutralButton("Discard...") { _, _ -> confirmDiscard(items.size) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDiscard(count: Int) {
        AlertDialog.Builder(this)
            .setTitle("Discard $count record(s)?")
            .setMessage("They are deleted from this phone and never reach the server.")
            .setPositiveButton("Discard") { _, _ ->
                db.discardRejected(session.username)
                refreshStatus()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ------------------------------------------------------------------ status

    private fun refreshStatus() {
        val rejected = db.getRejectedCount(session.username)
        val ofOthers = db.getPendingOfOthersCount(session.username)
        val sb = StringBuilder()
        sb.append("Herd on phone: ").append(db.getCachedCount()).append(" cattle\n")
        sb.append("Waiting to send: ").append(db.getPendingCount()).append(" | Need attention: ").append(rejected)
        if (ofOthers > 0) sb.append("\n").append(ofOthers).append(" of them were saved by another user and go out when that user signs in.")
        if (syncNote.isNotEmpty()) sb.append("\n").append(syncNote)
        tvSyncStatus.text = sb.toString()
        btnReviewRejected.visibility = if (rejected > 0) View.VISIBLE else View.GONE
        btnReviewRejected.text = "$rejected record(s) need attention - tap to review"
        updateSessionViews()
    }

    private fun setReaderStatus(msg: String) {
        tvReaderStatus.text = "RFID reader: $msg"
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)

        // When the barn network comes back, send what is waiting. The timer covers a network that never reports it.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { syncPending(false) }
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            networkCallback = null
        }
        uiHandler.post(retryLoop)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        uiHandler.removeCallbacks(retryLoop)
        val callback = networkCallback
        if (callback != null) {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // already unregistered
            }
            networkCallback = null
        }
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
        worker.shutdown()
    }

    companion object {
        private const val REQUEST_BLE = 101
        private const val RETRY_INTERVAL_MS = 60_000L
        private const val BATCH_SIZE = 100
        private const val APP_VERSION = "1.1"
        private const val NONE_LABEL = "Choose from the list..."
        private const val OTHER_LABEL = "Other (type it below)"
        private val RECORD_TYPES = listOf("VACCINATION", "TREATMENT", "HEALTH_CHECK", "QUARANTINE")
        private val HEALTH_STATUSES = listOf("HEALTHY", "UNDER_TREATMENT", "QUARANTINE")
    }
}
