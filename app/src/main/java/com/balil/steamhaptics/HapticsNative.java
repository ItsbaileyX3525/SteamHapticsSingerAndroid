package com.balil.steamhaptics;

public class HapticsNative {

	static {
		System.loadLibrary("steamhaptics");
	}

	public static final int CONTROLLER_ORIGINAL = 0; //Steam Controller (2015)
	public static final int CONTROLLER_TRITON = 1;   //Steam Controller (2026)
	public static final int CONTROLLER_JUPITER = 2;  //Steam Deck I mean doesn't really work but yea

	public interface PlaybackCallback {
		boolean sendPacket(byte[] data);

		void onNotePlayed(int channel, int note);

		void onPlaybackFinished();
	}

	public static native void nativeSetCallbackTarget(Object target);

	public static native void nativeSetOptions(
		boolean directVel,
		boolean tritonLimit,
		boolean tritonSwap,
		boolean noGainCurve,
		int lMod,
		int rMod,
		int nMod,
		int mMod
	);

	public static native void nativeLoadGainCurve(int[] trackpadCurve, int[] rumbleCurve);

	public static native void nativeRequestStop();

	public static native void nativePlaySong(
		String midiFilePath,
		int controllerType,
		int intervalUsec,
		boolean repeat
	);
}
