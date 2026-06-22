package com.balil.steamhaptics;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class HapticsPlayer implements HapticsNative.PlaybackCallback {

	private static final String TAG = "HapticsPlayer";

	private static final int HID_SET_REPORT_REQUEST_TYPE = 0x21;
	private static final int HID_SET_REPORT_REQUEST = 0x09;
	private static final int HID_SET_REPORT_VALUE = 0x0300;
	private static final int TRANSFER_TIMEOUT_MS = 1000;

	public interface NoteListener {
		void onNotePlayed(int channel, int note);
		void onPlaybackFinished();
	}

	private UsbControllerManager.ConnectedController controller;
	private NoteListener noteListener;
	private UsbEndpoint cachedOutEndpoint;
	private Thread playbackThread;

	public HapticsPlayer() {
		HapticsNative.nativeSetCallbackTarget(this);
	}

	public void setController(UsbControllerManager.ConnectedController controller) {
		this.controller = controller;
		this.cachedOutEndpoint = null;
	}

	public void setNoteListener(NoteListener listener) {
		this.noteListener = listener;
	}

	public void loadGainCurves(Context context, int controllerType) {
		new Thread(() -> {
			try {
				if (controllerType == HapticsNative.CONTROLLER_TRITON) {
					int[] trackpad = readGainCurveAsset(context, "gaincurve/Triton_Trackpads.txt");
					int[] rumble = readGainCurveAsset(context, "gaincurve/Triton_Rumble.txt");
					HapticsNative.nativeLoadGainCurve(trackpad, rumble);
				} else if (controllerType == HapticsNative.CONTROLLER_JUPITER) {
					int[] trackpad = readGainCurveAsset(context, "gaincurve/Jupiter.txt");
					HapticsNative.nativeLoadGainCurve(trackpad, null);
				}
			} catch (Exception e) {
				Log.w(TAG, "Could not load gain curve asset, defaulting to flat gain", e);
			}
		}, "gain-curve-loader").start();
	}

	private int[] readGainCurveAsset(Context context, String assetPath) throws IOException {
		int[] values = new int[128];
		try (InputStream is = context.getAssets().open(assetPath);
		     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			String[] tokens = sb.toString().split("[^0-9-]+");
			int count = 0;
			for (String t : tokens) {
				t = t.trim();
				if (!t.isEmpty() && count < 128) {
					try {
						values[count++] = Integer.parseInt(t);
					} catch (NumberFormatException e) {
					}
				}
			}
		}
		return values;
	}

	public void play(String midiFilePath, int controllerType, int intervalUsec, boolean repeat) {
		if (playbackThread != null && playbackThread.isAlive()) {
			Log.w(TAG, "play() called while already playing, ignoring");
			return;
		}
		playbackThread = new Thread(() ->
			HapticsNative.nativePlaySong(midiFilePath, controllerType, intervalUsec, repeat)
		, "haptics-playback");
		playbackThread.start();
	}

	public void stop() {
		HapticsNative.nativeRequestStop();
	}

	public void release() {
		stop();
		
		final UsbControllerManager.ConnectedController toRelease;
		synchronized (this) {
			toRelease = controller;
			controller = null;
			cachedOutEndpoint = null;
		}

		new Thread(() -> {
			// DO NOT call nativeSetCallbackTarget(null) here because we reuse this instance.
			// Re-calling it with null would break any new connection that happens while
			// this cleanup thread is running.
			
			if (toRelease != null && toRelease.connection != null) {
				try {
					Log.i(TAG, "Releasing USB interface and closing connection");
					toRelease.connection.releaseInterface(toRelease.usbInterface);
					toRelease.connection.close();
				} catch (Exception e) {
					Log.e(TAG, "Error releasing USB connection", e);
				}
			}
		}, "haptics-release").start();
	}

	/** Explicitly cleanup JNI when the app is actually closing */
	public void finalCleanup() {
		HapticsNative.nativeSetCallbackTarget(null);
	}

	@Override
	public boolean sendPacket(byte[] data) {
		if (controller == null || controller.connection == null) {
			return false;
		}

		if (controller.controllerType == HapticsNative.CONTROLLER_TRITON) {
			return sendHidWrite(data);
		} else {
			return sendControlTransfer(data);
		}
	}

	private boolean sendControlTransfer(byte[] data) {
		int interfaceNum = controller.usbInterface.getId();
		int result = controller.connection.controlTransfer(
			HID_SET_REPORT_REQUEST_TYPE,
			HID_SET_REPORT_REQUEST,
			HID_SET_REPORT_VALUE,
			interfaceNum,
			data,
			data.length,
			TRANSFER_TIMEOUT_MS
		);
		if (result < 0) {
			Log.e(TAG, "controlTransfer failed: " + result);
		}
		return result >= 0;
	}

	private boolean sendHidWrite(byte[] data) {
		UsbEndpoint outEndpoint = resolveOutEndpoint();
		if (outEndpoint == null) {
			Log.e(TAG, "No OUT endpoint");
			return false;
		}
		int result = controller.connection.bulkTransfer(outEndpoint, data, data.length, TRANSFER_TIMEOUT_MS);
		if (result < 0) {
			Log.e(TAG, "bulkTransfer failed: " + result);
		}
		return result >= 0;
	}

	private UsbEndpoint resolveOutEndpoint() {
		if (cachedOutEndpoint != null) return cachedOutEndpoint;

		UsbInterface iface = controller.usbInterface;
		for (int i = 0; i < iface.getEndpointCount(); i++) {
			UsbEndpoint endpoint = iface.getEndpoint(i);
			if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
				cachedOutEndpoint = endpoint;
				return endpoint;
			}
		}
		return null;
	}

	@Override
	public void onNotePlayed(int channel, int note) {
		if (noteListener != null) {
			Log.v(TAG, "Note played: ch " + channel + ", note " + note);
			noteListener.onNotePlayed(channel, note);
		}
	}

	@Override
	public void onPlaybackFinished() {
		if (noteListener != null) {
			noteListener.onPlaybackFinished();
		}
	}
}
