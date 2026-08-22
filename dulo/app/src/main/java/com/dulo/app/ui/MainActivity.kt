package com.dulo.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dulo.app.R
import com.dulo.app.engine.StockfishBridge
import com.dulo.app.service.FloatingBubbleService

/**
 * Startbildschirm von DuLo: Berechtigungen einholen und den Overlay-Dienst ein- und ausschalten.
 * Die eigentliche Bedienung läuft danach über die Blase auf dem Bildschirm.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleFloating: ToggleButton
    private lateinit var tvToggleStatus: TextView

    // Rückgabe der Bildschirmaufnahme-Freigabe (Android 14)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startBubbleServiceWithProjection(result.resultCode, result.data!!)
        } else {
            // Ohne Freigabe läuft nichts: der Umschalter darf dann nicht auf "an" stehen bleiben
            btnToggleFloating.isChecked = false
            updateToggleState()
            Toast.makeText(this, "Ohne Aufnahmeberechtigung startet DuLo nicht", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        StockfishBridge.init(this)

        // Umschalter: dieselbe Schaltfläche startet und beendet DuLo.
        // Ausgewertet wird der Zustand nach dem Antippen, den ToggleButton selbst umschaltet.
        btnToggleFloating = findViewById(R.id.btnToggleFloating)
        tvToggleStatus = findViewById(R.id.tvToggleStatus)
        updateToggleState()
        btnToggleFloating.setOnClickListener {
            // Start und Stopp laufen beide asynchron: hier wird nur die Statuszeile gesetzt.
            // Den tatsächlichen Zustand gleicht updateToggleState() in onResume wieder ab.
            if (btnToggleFloating.isChecked) {
                tvToggleStatus.text = "DuLo wird gestartet ..."
                checkOverlayPermissionAndRequestCapture()
            } else {
                tvToggleStatus.text = "DuLo ist aus – auf das Bild tippen zum Starten"
                stopBubbleService()
            }
        }
    }

    /**
     * Der Umschalter steht beim Zurückkehren in die App immer auf dem tatsächlichen Zustand des Dienstes,
     * auch wenn dieser zwischenzeitlich über den Beenden-Knopf im Menü oder vom System gestoppt wurde.
     */
    override fun onResume() {
        super.onResume()
        updateToggleState()
    }

    /** Umschalter und Statuszeile auf den tatsächlichen Zustand des Dienstes bringen */
    private fun updateToggleState() {
        val running = FloatingBubbleService.isRunning
        btnToggleFloating.isChecked = running
        tvToggleStatus.text = if (running) {
            "DuLo läuft – auf das Bild tippen zum Beenden"
        } else {
            "DuLo ist aus – auf das Bild tippen zum Starten"
        }
    }

    /** Umschalter auf "aus": den Vordergrunddienst über seine Stopp-Aktion beenden */
    private fun stopBubbleService() {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_STOP
        }
        startService(stopIntent)
        Toast.makeText(this, "DuLo wird beendet", Toast.LENGTH_SHORT).show()
    }

    private fun checkOverlayPermissionAndRequestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Ohne Overlay-Berechtigung bleibt der Umschalter aus, bis der Nutzer aus den Einstellungen zurückkommt
            btnToggleFloating.isChecked = false
            updateToggleState()
            Toast.makeText(this, "Bitte zuerst die Overlay-Berechtigung erteilen, damit die Züge über Duolingo angezeigt werden können", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // Freigabe der Bildschirmaufnahme nach Android 14 anfordern
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startBubbleServiceWithProjection(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingBubbleService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "DuLo ist gestartet. Jetzt Duolingo öffnen und auf die Blase tippen", Toast.LENGTH_SHORT).show()
        finish() // Fenster schließen und zurück zum Startbildschirm
    }

    override fun onDestroy() {
        super.onDestroy()
        // Die Stockfish-Engine bleibt für den FloatingBubbleService bestehen und wird hier nicht freigegeben
    }
}
