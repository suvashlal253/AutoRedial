package com.autoredial.pro

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.autoredial.pro.databinding.ActivityMainBinding
import com.autoredial.pro.service.CallAudioMonitoringService
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isRedialingActive = false
    private var targetPhoneNumber: String = ""
    private var toneGenerator: ToneGenerator? = null

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CallAudioMonitoringService.ACTION_CALL_STATE_CHANGED -> {
                    val state = intent.getStringExtra(CallAudioMonitoringService.EXTRA_STATE_NAME) ?: "IDLE"
                    val detail = intent.getStringExtra(CallAudioMonitoringService.EXTRA_STATE_DETAIL) ?: ""
                    val attempts = intent.getIntExtra(CallAudioMonitoringService.EXTRA_ATTEMPT_COUNT, 1)
                    val cutLatencyMs = intent.getLongExtra(CallAudioMonitoringService.EXTRA_CUT_LATENCY_MS, -1L)
                    val vadEnergy = intent.getFloatExtra(CallAudioMonitoringService.EXTRA_VAD_ENERGY, 0f)

                    updateUiState(state, detail, attempts, cutLatencyMs, vadEnergy)
                }
                CallAudioMonitoringService.ACTION_REDIAL_TERMINATED -> {
                    val reason = intent.getStringExtra(CallAudioMonitoringService.EXTRA_TERMINATION_REASON) ?: "Stopped"
                    onRedialStopped(reason)
                }
            }
        }
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        contactUri?.let { uri ->
            retrievePhoneNumberFromContact(uri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val callPhoneGranted = permissions[Manifest.permission.CALL_PHONE] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (callPhoneGranted && recordAudioGranted) {
            checkDefaultDialerRoleAndStart()
        } else {
            Toast.makeText(this, "Permissions required for auto-dialing & audio detection", Toast.LENGTH_LONG).show()
        }
    }

    private val roleManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startRedialEngine()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("autoredial_prefs", Context.MODE_PRIVATE)
        initToneGenerator()
        setupListeners()
        loadSavedSettings()
        registerBroadcastReceiver()
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 80)
        } catch (_: Exception) {}
    }

    private fun setupListeners() {
        binding.btnStartRedial.setOnClickListener {
            val phone = binding.etPhoneNumber.text?.toString()?.trim() ?: ""
            if (phone.isEmpty()) {
                binding.tilPhoneNumber.error = "Please enter or pick a phone number"
                return@setOnClickListener
            }
            binding.tilPhoneNumber.error = null
            targetPhoneNumber = phone
            saveLastUsedNumber(phone)
            requestRequiredPermissions()
        }

        binding.fabStopRedial.setOnClickListener {
            stopRedialEngine("User pressed Stop")
        }

        binding.btnOpenDialpad.setOnClickListener {
            showDialpadBottomSheet()
        }

        binding.btnPickContact.setOnClickListener {
            contactPickerLauncher.launch(null)
        }

        binding.tvAddHotline.setOnClickListener {
            showAddHotlineDialog()
        }

        binding.chipDoctorClinic.setOnClickListener {
            binding.etPhoneNumber.setText("+1 (555) 019-2834")
        }
        binding.chipEmergency.setOnClickListener {
            binding.etPhoneNumber.setText("+880 1711 000000")
        }
        binding.chipSupport.setOnClickListener {
            binding.etPhoneNumber.setText("333")
        }

        binding.sliderRedialDelay.addOnChangeListener { _, value, _ ->
            val ms = value.toInt()
            binding.tvDelayValue.text = if (ms == 0) "0 ms (Instant Cut)" else "$ms ms"
            prefs.edit().putInt("redial_delay_ms", ms).apply()
        }

        binding.btnDelay0ms.setOnClickListener { binding.sliderRedialDelay.value = 0f }
        binding.btnDelay50ms.setOnClickListener { binding.sliderRedialDelay.value = 50f }
        binding.btnDelay250ms.setOnClickListener { binding.sliderRedialDelay.value = 250f }
        binding.btnDelay1000ms.setOnClickListener { binding.sliderRedialDelay.value = 1000f }

        binding.switchAutoSpeaker.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_speaker_enabled", isChecked).apply()
        }
        binding.switchMuteRedials.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mute_redials_enabled", isChecked).apply()
        }
    }

    private fun loadSavedSettings() {
        val savedDelay = prefs.getInt("redial_delay_ms", 50)
        binding.sliderRedialDelay.value = savedDelay.toFloat().coerceIn(0f, 2000f)
        binding.tvDelayValue.text = "$savedDelay ms"

        binding.switchAutoSpeaker.isChecked = prefs.getBoolean("auto_speaker_enabled", true)
        binding.switchMuteRedials.isChecked = prefs.getBoolean("mute_redials_enabled", true)

        val lastNumber = prefs.getString("last_dialed_number", "")
        if (!lastNumber.isNullOrEmpty()) {
            binding.etPhoneNumber.setText(lastNumber)
        }
    }

    private fun saveLastUsedNumber(number: String) {
        prefs.edit().putString("last_dialed_number", number).apply()
    }

    private fun retrievePhoneNumberFromContact(contactUri: Uri) {
        val cursor: Cursor? = contentResolver.query(contactUri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                if (idIndex != -1 && hasPhoneIndex != -1) {
                    val contactId = it.getString(idIndex)
                    val hasPhone = it.getInt(hasPhoneIndex)

                    if (hasPhone > 0) {
                        val phoneCursor: Cursor? = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )
                        phoneCursor?.use { pCursor ->
                            if (pCursor.moveToFirst()) {
                                val numberIndex = pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                if (numberIndex != -1) {
                                    val phone = pCursor.getString(numberIndex)
                                    binding.etPhoneNumber.setText(phone.replace(" ", ""))
                                    Toast.makeText(this, "Selected: $phone", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showDialpadBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_AutoRedial_BottomSheet)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_dialpad, null)
        dialog.setContentView(sheetView)

        val tvDisplay = sheetView.findViewById<TextView>(R.id.tvDialpadDisplay)
        val btnBackspace = sheetView.findViewById<ImageView>(R.id.btnDialpadBackspace)
        val btnApply = sheetView.findViewById<View>(R.id.btnDialpadApply)

        tvDisplay.text = binding.etPhoneNumber.text?.toString() ?: ""

        fun appendDigit(digit: String, tone: Int) {
            tvDisplay.append(digit)
            toneGenerator?.startTone(tone, 100)
            triggerHapticFeedback(15)
        }

        sheetView.findViewById<View>(R.id.key1)?.setOnClickListener { appendDigit("1", ToneGenerator.TONE_DTMF_1) }
        sheetView.findViewById<View>(R.id.key2)?.setOnClickListener { appendDigit("2", ToneGenerator.TONE_DTMF_2) }
        sheetView.findViewById<View>(R.id.key3)?.setOnClickListener { appendDigit("3", ToneGenerator.TONE_DTMF_3) }
        sheetView.findViewById<View>(R.id.key4)?.setOnClickListener { appendDigit("4", ToneGenerator.TONE_DTMF_4) }
        sheetView.findViewById<View>(R.id.key5)?.setOnClickListener { appendDigit("5", ToneGenerator.TONE_DTMF_5) }
        sheetView.findViewById<View>(R.id.key6)?.setOnClickListener { appendDigit("6", ToneGenerator.TONE_DTMF_6) }
        sheetView.findViewById<View>(R.id.key7)?.setOnClickListener { appendDigit("7", ToneGenerator.TONE_DTMF_7) }
        sheetView.findViewById<View>(R.id.key8)?.setOnClickListener { appendDigit("8", ToneGenerator.TONE_DTMF_8) }
        sheetView.findViewById<View>(R.id.key9)?.setOnClickListener { appendDigit("9", ToneGenerator.TONE_DTMF_9) }
        sheetView.findViewById<View>(R.id.keyStar)?.setOnClickListener { appendDigit("*", ToneGenerator.TONE_DTMF_S) }
        sheetView.findViewById<View>(R.id.key0)?.setOnClickListener { appendDigit("0", ToneGenerator.TONE_DTMF_0) }
        sheetView.findViewById<View>(R.id.keyHash)?.setOnClickListener { appendDigit("#", ToneGenerator.TONE_DTMF_P) }

        sheetView.findViewById<View>(R.id.key0)?.setOnLongClickListener {
            tvDisplay.append("+")
            toneGenerator?.startTone(ToneGenerator.TONE_DTMF_0, 150)
            triggerHapticFeedback(30)
            true
        }

        btnBackspace?.setOnClickListener {
            val current = tvDisplay.text.toString()
            if (current.isNotEmpty()) {
                tvDisplay.text = current.substring(0, current.length - 1)
                triggerHapticFeedback(15)
            }
        }
        btnBackspace?.setOnLongClickListener {
            tvDisplay.text = ""
            triggerHapticFeedback(40)
            true
        }

        btnApply?.setOnClickListener {
            binding.etPhoneNumber.setText(tvDisplay.text.toString())
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAddHotlineDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_hotline, null)
        val etName = dialogView.findViewById<EditText>(R.id.etHotlineName)
        val etNumber = dialogView.findViewById<EditText>(R.id.etHotlineNumber)

        etNumber.setText(binding.etPhoneNumber.text?.toString() ?: "")

        AlertDialog.Builder(this, R.style.Theme_AutoRedial_Dialog)
            .setTitle("Save Quick Contact / Hotline")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val number = etNumber.text.toString().trim()
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    addHotlineChip(name, number)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHotlineChip(name: String, number: String) {
        val chip = Chip(this).apply {
            text = "$name ($number)"
            isClickable = true
            isCheckable = false
            setChipIconResource(R.drawable.ic_call)
            setOnClickListener {
                binding.etPhoneNumber.setText(number)
                Toast.makeText(this@MainActivity, "Loaded: $name", Toast.LENGTH_SHORT).show()
            }
        }
        binding.layoutHotlineChips.addView(chip, 0)
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            checkDefaultDialerRoleAndStart()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkDefaultDialerRoleAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                roleManagerLauncher.launch(intent)
                return
            }
        }
        startRedialEngine()
    }

    private fun startRedialEngine() {
        isRedialingActive = true
        binding.btnStartRedial.visibility = View.INVISIBLE
        binding.fabStopRedial.visibility = View.VISIBLE
        binding.tilPhoneNumber.isEnabled = false
        binding.btnOpenDialpad.isEnabled = false
        binding.btnPickContact.isEnabled = false

        val delayMs = binding.sliderRedialDelay.value.toLong()
        val autoSpeaker = binding.switchAutoSpeaker.isChecked
        val muteRedials = binding.switchMuteRedials.isChecked

        val serviceIntent = Intent(this, CallAudioMonitoringService::class.java).apply {
            action = CallAudioMonitoringService.ACTION_START_REDIAL
            putExtra(CallAudioMonitoringService.EXTRA_PHONE_NUMBER, targetPhoneNumber)
            putExtra(CallAudioMonitoringService.EXTRA_REDIAL_DELAY_MS, delayMs)
            putExtra(CallAudioMonitoringService.EXTRA_AUTO_SPEAKER, autoSpeaker)
            putExtra(CallAudioMonitoringService.EXTRA_MUTE_REDIALS, muteRedials)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        triggerHapticFeedback(40)
    }

    private fun stopRedialEngine(reason: String) {
        val serviceIntent = Intent(this, CallAudioMonitoringService::class.java).apply {
            action = CallAudioMonitoringService.ACTION_STOP_REDIAL
        }
        startService(serviceIntent)
        onRedialStopped(reason)
    }

    private fun onRedialStopped(reason: String) {
        isRedialingActive = false
        binding.btnStartRedial.visibility = View.VISIBLE
        binding.fabStopRedial.visibility = View.GONE
        binding.tilPhoneNumber.isEnabled = true
        binding.btnOpenDialpad.isEnabled = true
        binding.btnPickContact.isEnabled = true
        binding.viewPulseHalo.visibility = View.INVISIBLE

        binding.tvCallState.text = "STOPPED"
        binding.tvStateDetail.text = reason
        binding.progressVadLevel.progress = 0
        binding.tvVadThresholdValue.text = "0 dB"
        triggerHapticFeedback(50)
    }

    private fun updateUiState(state: String, detail: String, attempts: Int, cutLatencyMs: Long, vadEnergy: Float) {
        binding.tvCallState.text = state
        binding.tvStateDetail.text = detail
        binding.tvAttemptCount.text = attempts.toString()

        if (cutLatencyMs > 0) {
            binding.tvLastCutSpeed.text = "$cutLatencyMs ms"
        }

        val progressInt = (vadEnergy.coerceIn(0f, 1f) * 100).toInt()
        binding.progressVadLevel.progress = progressInt
        binding.tvVadThresholdValue.text = "${(progressInt * 0.6).toInt()} dB"

        when (state) {
            "DIALING...", "DETECTING AUDIO..." -> {
                binding.viewPulseHalo.visibility = View.VISIBLE
            }
            "RINGING..." -> {
                binding.viewPulseHalo.visibility = View.VISIBLE
                if (binding.switchAutoSpeaker.isChecked) {
                    binding.tvStateDetail.text = "Ringing (Speaker ON - Handsfree Active)"
                }
            }
            "CONNECTED!" -> {
                binding.viewPulseHalo.visibility = View.INVISIBLE
                Toast.makeText(this, "Call Connected! Auto-redial stopped automatically.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(CallAudioMonitoringService.ACTION_CALL_STATE_CHANGED)
            addAction(CallAudioMonitoringService.ACTION_REDIAL_TERMINATED)
        }
        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun triggerHapticFeedback(durationMs: Long = 30) {
        val vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
        toneGenerator?.release()
    }
}
