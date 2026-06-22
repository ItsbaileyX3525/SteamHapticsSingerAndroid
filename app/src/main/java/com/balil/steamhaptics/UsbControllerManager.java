package com.balil.steamhaptics;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;

public class UsbControllerManager {

	private static final String TAG = "UsbControllerManager";
	private static final String ACTION_USB_PERMISSION = "com.balil.steamhaptics.USB_PERMISSION";

	private static final int VALVE_VID = 0x28DE;
	private static final int STEAM_CONTROLLER = 0x1101;
	private static final int STEAM_CONTROLLER_2015 = 0x1102;
	private static final int STEAM_DONGLE = 0x1142;
	private static final int STEAM_CONTROLLER_2026 = 0x1302;
	private static final int STEAM_PUCK = 0x1304;
	private static final int STEAM_DECK = 0x1205;

	public static class ConnectedController {
		public final UsbDevice device;
		public final UsbDeviceConnection connection;
		public final UsbInterface usbInterface;
		public final int controllerType;

		ConnectedController(UsbDevice device, UsbDeviceConnection connection, UsbInterface usbInterface, int controllerType) {
			this.device = device;
			this.connection = connection;
			this.usbInterface = usbInterface;
			this.controllerType = controllerType;
		}
	}

	public interface ConnectionCallback {
		void onConnected(ConnectedController controller);
		void onPermissionDenied(UsbDevice device);
		void onNoDeviceFound();
		void onDisconnected(UsbDevice device);
	}

	private final Context context;
	private final UsbManager usbManager;
	private BroadcastReceiver usbReceiver;
	private boolean isConnecting = false;
	private final Handler handler = new Handler(Looper.getMainLooper());

	public UsbControllerManager(Context context) {
		this.context = context.getApplicationContext();
		this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
	}

	public void connectToSupportedDevice(ConnectionCallback callback) {
		handler.removeCallbacksAndMessages(null);
		handler.postDelayed(() -> isConnecting = false, 10000);

		if (isConnecting) {
			Log.i(TAG, "Connection attempt in progress... allowing retry to bypass potential stuck state.");
		}
		isConnecting = true;

		Map<String, UsbDevice> deviceList = usbManager.getDeviceList();
		UsbDevice target = null;
		int controllerType = -1;

		for (UsbDevice device : deviceList.values()) {
			if (device.getVendorId() != VALVE_VID) continue;

			int pid = device.getProductId();
			if (pid == STEAM_CONTROLLER || pid == STEAM_CONTROLLER_2015 || pid == STEAM_DONGLE) {
				target = device;
				controllerType = HapticsNative.CONTROLLER_ORIGINAL;
				break;
			} else if (pid == STEAM_CONTROLLER_2026 || pid == STEAM_PUCK) {
				target = device;
				controllerType = HapticsNative.CONTROLLER_TRITON;
				break;
			} else if (pid == STEAM_DECK) {
				target = device;
				controllerType = HapticsNative.CONTROLLER_JUPITER;
				break;
			}
		}

		if (target == null) {
			Log.i(TAG, "No supported Steam controller found");
			isConnecting = false;
			callback.onNoDeviceFound();
			return;
		}

		final UsbDevice finalTarget = target;
		final int finalType = controllerType;

		registerUsbReceiver(callback, finalType);

		if (usbManager.hasPermission(target)) {
			Log.i(TAG, "Permission already granted for " + target.getDeviceName());
			new Thread(() -> openAndClaim(finalTarget, finalType, callback)).start();
			return;
		}

		Log.i(TAG, "Requesting USB permission for " + target.getDeviceName());
		Intent intent = new Intent(ACTION_USB_PERMISSION);
		intent.setPackage(context.getPackageName());
		
		int flags = PendingIntent.FLAG_MUTABLE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			// Redundant but safe
			flags |= 0;
		}

		PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
		usbManager.requestPermission(target, permissionIntent);
	}

	private void registerUsbReceiver(ConnectionCallback callback, final int expectedType) {
		if (usbReceiver != null) {
			try {
				context.unregisterReceiver(usbReceiver);
			} catch (Exception ignored) {}
		}

		usbReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context ctx, Intent intent) {
				String action = intent.getAction();
				if (ACTION_USB_PERMISSION.equals(action)) {
					UsbDevice device = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ?
						intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class) :
						intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					
					boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
					
					Log.i(TAG, "USB Permission result: " + granted);

					if (device != null) {
						if (granted || usbManager.hasPermission(device)) {
							Log.i(TAG, "Permission confirmed. Opening device...");
							new Thread(() -> openAndClaim(device, expectedType, callback)).start();
						} else {
							Log.w(TAG, "Permission denied by user.");
							isConnecting = false;
							callback.onPermissionDenied(device);
						}
					}
				} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
					UsbDevice device = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ?
						intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class) :
						intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					if (device != null) {
						Log.i(TAG, "USB detached: " + device.getDeviceName());
						callback.onDisconnected(device);
					}
				}
			}
		};

		IntentFilter filter = new IntentFilter();
		filter.addAction(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

		//android 14 system broadcasts for USB permissions often require RECEIVER_EXPORTED
		//even if the intent is restricted to ts app.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
		} else {
			context.registerReceiver(usbReceiver, filter);
		}
	}

	private void openAndClaim(UsbDevice device, int controllerType, ConnectionCallback callback) {
		try {
			Log.i(TAG, "Attempting to open device...");
			UsbDeviceConnection connection = usbManager.openDevice(device);
			if (connection == null) {
				Log.e(TAG, "openDevice returned null");
				isConnecting = false;
				callback.onNoDeviceFound();
				return;
			}

			UsbInterface usbInterface = null;
			for (int i = 0; i < device.getInterfaceCount(); i++) {
				UsbInterface iface = device.getInterface(i);
				boolean hasOut = false;
				for (int j = 0; j < iface.getEndpointCount(); j++) {
					if (iface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
						hasOut = true;
						break;
					}
				}
				
				if (controllerType != HapticsNative.CONTROLLER_TRITON && iface.getId() == 2) {
					usbInterface = iface;
					break;
				}
				if (usbInterface == null && hasOut) {
					usbInterface = iface;
				}
			}
			
			if (usbInterface == null && device.getInterfaceCount() > 0) {
				usbInterface = device.getInterface(0);
			}

			if (usbInterface == null) {
				Log.e(TAG, "No suitable interface found");
				connection.close();
				isConnecting = false;
				callback.onNoDeviceFound();
				return;
			}

			Log.i(TAG, "Claiming interface " + usbInterface.getId());
			if (connection.claimInterface(usbInterface, true)) {
				Log.i(TAG, "Connection successful!");
				isConnecting = false;
				callback.onConnected(new ConnectedController(device, connection, usbInterface, controllerType));
			} else {
				Log.e(TAG, "Failed to claim interface");
				connection.close();
				isConnecting = false;
				callback.onNoDeviceFound();
			}
		} catch (Exception e) {
			Log.e(TAG, "Error during connection handshake", e);
			isConnecting = false;
			callback.onNoDeviceFound();
		}
	}

	public void cleanup() {
		handler.removeCallbacksAndMessages(null);
		if (usbReceiver != null) {
			try {
				context.unregisterReceiver(usbReceiver);
			} catch (Exception ignored) {}
			usbReceiver = null;
		}
		isConnecting = false;
	}
}
