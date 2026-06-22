package com.balil.steamhaptics;

import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity
	implements UsbControllerManager.ConnectionCallback, HapticsPlayer.NoteListener {

	private static final String TAG = "MainActivity";
	private static final int DEFAULT_INTERVAL_USEC = 10000;

	private static final String[] NOTE_NAMES = {
		"C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-"
	};

	private UsbControllerManager usbControllerManager;
	private HapticsPlayer hapticsPlayer;
	private UsbControllerManager.ConnectedController connectedController;

	private TextView statusText;
	private TextView selectedFileText;
	private TextView nowPlayingText;
	private final TextView[] channelTexts = new TextView[4];
	private CheckBox repeatCheckBox;

	private String selectedMidiPath;

	private final ActivityResultLauncher<String[]> midiPickerLauncher =
		registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onMidiFilePicked);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.d(TAG, "Lifecycle: onCreate");
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		usbControllerManager = new UsbControllerManager(this);
		hapticsPlayer = new HapticsPlayer();
		hapticsPlayer.setNoteListener(this);

		statusText = findViewById(R.id.statusText);
		selectedFileText = findViewById(R.id.selectedFileText);
		nowPlayingText = findViewById(R.id.nowPlayingText);
		
		channelTexts[0] = findViewById(R.id.channel0Text);
		channelTexts[1] = findViewById(R.id.channel1Text);
		channelTexts[2] = findViewById(R.id.channel2Text);
		channelTexts[3] = findViewById(R.id.channel3Text);

		repeatCheckBox = findViewById(R.id.repeatCheckBox);

		Button connectButton = findViewById(R.id.connectButton);
		Button pickMidiButton = findViewById(R.id.pickMidiButton);
		Button playButton = findViewById(R.id.playButton);
		Button stopButton = findViewById(R.id.stopButton);

		connectButton.setOnClickListener(v -> connectToController());
		pickMidiButton.setOnClickListener(v -> midiPickerLauncher.launch(new String[]{"audio/midi", "audio/mid", "*/*"}));
		playButton.setOnClickListener(v -> startPlayback());
		stopButton.setOnClickListener(v -> {
			hapticsPlayer.stop();
			clearChannelStatus();
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.d(TAG, "Lifecycle: onStart");
	}

	@Override
	protected void onResume() {
		super.onResume();
		Log.d(TAG, "Lifecycle: onResume");
	}

	@Override
	protected void onPause() {
		super.onPause();
		Log.d(TAG, "Lifecycle: onPause");
	}

	@Override
	protected void onStop() {
		super.onStop();
		Log.d(TAG, "Lifecycle: onStop (isFinishing=" + isFinishing() + ")");
	}

	@Override
	protected void onDestroy() {
		Log.d(TAG, "Lifecycle: onDestroy (isFinishing=" + isFinishing() + ")");
		super.onDestroy();
		hapticsPlayer.stop();
		hapticsPlayer.release();
		hapticsPlayer.finalCleanup();
		usbControllerManager.cleanup();
	}

	private void connectToController() {
		hapticsPlayer.stop();
		statusText.setText("Looking for controller...");
		usbControllerManager.connectToSupportedDevice(this);
	}

	@Override
	public void onConnected(UsbControllerManager.ConnectedController controller) {
		if (this.connectedController != null) {
			hapticsPlayer.release();
		}
		this.connectedController = controller;
		hapticsPlayer.setController(controller);
		hapticsPlayer.loadGainCurves(this, controller.controllerType);

		runOnUiThread(() -> {
			String typeName = controllerTypeName(controller.controllerType);
			statusText.setText("Connected: " + typeName);
		});
	}

	@Override
	public void onPermissionDenied(UsbDevice device) {
		runOnUiThread(() -> {
			statusText.setText("USB permission denied");
			Toast.makeText(this, "Permission denied for controller", Toast.LENGTH_SHORT).show();
		});
	}

	@Override
	public void onNoDeviceFound() {
		runOnUiThread(() -> statusText.setText("No supported controller found."));
	}

	@Override
	public void onDisconnected(UsbDevice device) {
		runOnUiThread(() -> {
			Toast.makeText(this, "Controller Disconnected", Toast.LENGTH_SHORT).show();
			if (connectedController != null && connectedController.device.equals(device)) {
				hapticsPlayer.stop();
				hapticsPlayer.release();
				connectedController = null;
				statusText.setText("Controller disconnected");
				clearChannelStatus();
			}
		});
	}

	private String controllerTypeName(int type) {
		if (type == HapticsNative.CONTROLLER_ORIGINAL) return "Steam Controller (2015)";
		if (type == HapticsNative.CONTROLLER_TRITON) return "Steam Controller (2026)";
		if (type == HapticsNative.CONTROLLER_JUPITER) return "Steam Deck";
		return "Unknown";
	}

	private void onMidiFilePicked(Uri uri) {
		if (uri == null) return;

		String displayName = null;
		if ("content".equals(uri.getScheme())) {
			try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
				if (cursor != null && cursor.moveToFirst()) {
					int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
					if (nameIndex != -1) {
						displayName = cursor.getString(nameIndex);
					}
				}
			}
		}
		if (displayName == null) {
			displayName = uri.getLastPathSegment();
		}

		try {
			File cacheFile = new File(getCacheDir(), "selected.mid");
			try (InputStream in = getContentResolver().openInputStream(uri);
			     OutputStream out = new FileOutputStream(cacheFile)) {
				if (in == null) throw new IOException("Could not open input stream");
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) != -1) {
					out.write(buffer, 0, read);
				}
			}
			selectedMidiPath = cacheFile.getAbsolutePath();
			selectedFileText.setText("Selected: " + displayName);
		} catch (IOException e) {
			Log.e(TAG, "Failed to copy MIDI file", e);
			Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show();
		}
	}

	private void startPlayback() {
		if (connectedController == null) {
			Toast.makeText(this, "Connect to a controller first", Toast.LENGTH_SHORT).show();
			return;
		}
		if (selectedMidiPath == null) {
			Toast.makeText(this, "Choose a MIDI file first", Toast.LENGTH_SHORT).show();
			return;
		}

		clearChannelStatus();

		HapticsNative.nativeSetOptions(
			false,
			false,
			false,
			false,
			0, 0, 0, 0
		);

		nowPlayingText.setText("Starting playback...");
		hapticsPlayer.play(selectedMidiPath, connectedController.controllerType, DEFAULT_INTERVAL_USEC, repeatCheckBox.isChecked());
	}

	private void clearChannelStatus() {
		runOnUiThread(() -> {
			for (int i = 0; i < 4; i++) {
				if (channelTexts[i] != null) {
					channelTexts[i].setText("CH " + i + ": -");
				}
			}
		});
	}

	@Override
	public void onNotePlayed(int channel, int note) {
		runOnUiThread(() -> {
			if (channel >= 0 && channel < 4 && channelTexts[channel] != null) {
				if (note < 0) {
					channelTexts[channel].setText("CH " + channel + ": Off");
				} else {
					String name = NOTE_NAMES[note % 12];
					int octave = (note / 12) - 1;
					channelTexts[channel].setText("CH " + channel + ": " + name + octave);
				}
			}
		});
	}

	@Override
	public void onPlaybackFinished() {
		runOnUiThread(() -> {
			nowPlayingText.setText("Playback finished");
			if (connectedController == null) {
				statusText.setText("Controller disconnected");
			}
			clearChannelStatus();
		});
	}
}
