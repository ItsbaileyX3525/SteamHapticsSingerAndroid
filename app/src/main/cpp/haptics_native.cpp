// haptics_native.cpp
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <atomic>
#include <chrono>
#include <thread>
#include <mutex>

extern "C" {
#include "midifile.h"
}

#define LOG_TAG "SteamHapticsNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define STEAM_CONTROLLER_MAGIC_PERIOD_RATIO 495483.0
#define CHANNEL_COUNT 4
#define NOTE_STOP -1
#define DEFAULT_GAIN 0

enum class ControllerType : int32_t {
	Original = 0,
	Triton = 1,
	Jupiter = 2
};

static double midiFrequency[128] = {0, 8.662, 9.177, 9.723, 10.301, 10.913, 11.562, 12.250, 12.978, 13.750, 14.568, 15.434, 16.352, 17.324, 18.354, 19.445, 20.602, 21.827, 23.125, 24.500, 25.957, 27.500, 29.135, 30.868, 32.703, 34.648, 36.708, 38.891, 41.203, 43.654, 46.249, 48.999, 51.913, 55.000, 58.270, 61.735, 65.406, 69.296, 73.416, 77.782, 82.407, 87.307, 92.499, 97.999, 103.826, 110.000, 116.541, 123.471, 130.813, 138.591, 146.832, 155.563, 164.814, 174.614, 184.997, 195.998, 207.652, 220.000, 233.082, 246.942, 261.626, 277.183, 293.665, 311.127, 329.628, 349.228, 369.994, 391.995, 415.305, 440.000, 466.164, 493.883, 523.251, 554.365, 587.330, 622.254, 659.255, 698.456, 739.989, 783.991, 830.609, 880.000, 932.328, 987.767, 1046.502, 1108.731, 1174.659, 1244.508, 1318.510, 1396.913, 1479.978, 1567.982, 1661.219, 1760.000, 1864.655, 1975.533, 2093.005, 2217.461, 2349.318, 2489.016, 2637.020, 2793.826, 2959.955, 3135.963, 3322.438, 3520.000, 3729.310, 3951.066, 4186.009, 4434.922, 4698.636, 4978.032, 5274.041, 5587.652, 5919.911, 6271.927, 6644.875, 7040.000, 7458.620, 7902.133, 8372.018, 8869.844, 9397.273, 9956.063, 10548.082, 11175.303, 11839.822, 12543.854};
static uint16_t midiFrequencyTr[128] = {0, 10, 10, 11, 11, 12, 13, 13, 14, 15, 16, 16, 17, 18, 19, 20, 22, 23, 24, 25, 27, 29, 30, 32, 34, 36, 38, 40, 42, 45, 47, 50, 53, 56, 59, 63, 66, 70, 75, 80, 84, 89, 94, 100, 107, 113, 120, 126, 134, 142, 151, 160, 169, 179, 189, 200, 213, 226, 239, 253, 267, 283, 300, 318, 336, 357, 377, 399, 423, 449, 477, 505, 535, 566, 598, 636, 674, 713, 756, 800, 848, 898, 951, 1008, 1068, 1131, 1199, 1270, 1345, 1425, 1510, 1600, 1693, 1792, 1897, 2008, 2125, 2249, 2381, 2521, 2669, 2826, 2992, 3168, 3354, 3552, 3761, 3983, 4218, 4467, 4731, 5010, 5306, 5620, 5952, 6304, 6677, 7072, 7491, 7934, 8404, 8902, 9429, 9988, 10580, 11207, 11872, 12576};
static uint16_t midiFrequencyRb[128] = {0, 9, 9, 10, 10, 11, 12, 12, 13, 14, 15, 15, 16, 17, 18, 19, 21, 22, 23, 24, 26, 28, 29, 31, 33, 35, 37, 39, 41, 44, 46, 49, 52, 55, 58, 62, 65, 69, 73, 78, 82, 87, 92, 98, 104, 110, 117, 123, 131, 139, 147, 156, 165, 175, 185, 196, 208, 220, 233, 247, 261, 276, 293, 310, 328, 349, 369, 391, 414, 439, 466, 493, 522, 552, 584, 621, 658, 696, 738, 781, 828, 877, 929, 985, 1043, 1105, 1171, 1240, 1314, 1392, 1475, 1562, 1655, 1754, 1858, 1969, 2085, 2209, 2340, 2480, 2627, 2784, 2949, 3124, 3311, 3507, 3716, 3938, 4173, 4422, 4686, 4965, 5261, 5575, 5907, 6259, 6632, 7027, 7446, 7889, 8359, 8857, 9384, 9943, 10535, 11162, 11827, 12531};

static uint8_t gainCurve[128] = {DEFAULT_GAIN};
static uint8_t gainCurveRb[128] = {DEFAULT_GAIN};

static std::atomic<bool> g_directVel{false};
static std::atomic<bool> g_tritonLimit{false};
static std::atomic<bool> g_tritonSwap{false};
static std::atomic<bool> g_stopRequested{false};
static std::atomic<bool> g_noGainCurve{false};
static int g_channelCount = 2;
static int g_gainModifier[5] = {0};

static JavaVM* g_jvm = nullptr;
static std::mutex g_callbackMutex;
static jobject g_callbackTarget = nullptr;
static jmethodID g_sendPacketMethod = nullptr;
static jmethodID g_onNotePlayedMethod = nullptr;
static jmethodID g_onPlaybackFinishedMethod = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
	g_jvm = vm;
	return JNI_VERSION_1_6;
}

static int buildHapticPacket(ControllerType type, int channel, int note, int velocity, uint8_t outBuf[65]) {
	memset(outBuf, 0, 65);
	double frequency = midiFrequency[note < 0 ? 0 : note];
	uint16_t duration = (note == NOTE_STOP) ? 0x0000 : 0x7fff;
	switch (type) {
		case ControllerType::Original: {
			double period = 1.0 / frequency;
			uint16_t periodCommand = (uint16_t)(period * STEAM_CONTROLLER_MAGIC_PERIOD_RATIO);
			uint16_t repeatCommand = (note == NOTE_STOP) ? 0x0000 : 0x7fff;
			outBuf[0] = 0x8F;
			outBuf[2] = (uint8_t)channel;
			outBuf[3] = periodCommand % 0xFF;
			outBuf[4] = periodCommand / 0xFF;
			outBuf[5] = periodCommand % 0xFF;
			outBuf[6] = periodCommand / 0xFF;
			outBuf[7] = repeatCommand % 0xFF;
			outBuf[8] = repeatCommand / 0xFF;
			return 64;
		}
		case ControllerType::Triton: {
			int haptic = channel ^ 1;
			if (!g_tritonSwap.load()) haptic = haptic ^ 2;
			haptic = haptic + (haptic >> 1);
			uint16_t freq = (haptic < 2) ? midiFrequencyTr[note < 0 ? 0 : note] : midiFrequencyRb[note < 0 ? 0 : note];
			if (note == NOTE_STOP) {
				outBuf[0] = 0x82;
				outBuf[1] = (uint8_t)haptic;
			} else {
				outBuf[0] = 0x83;
				outBuf[1] = (uint8_t)haptic;
				outBuf[2] = (uint8_t)(((g_directVel.load()) ? (velocity * 255) / 127 - 128 : 0xFE) + g_gainModifier[haptic]);
				outBuf[3] = freq % 0xFF;
				outBuf[4] = freq / 0xFF;
				outBuf[5] = 0xFF;
				outBuf[6] = 0x7F;
			}
			return 65;
		}
		case ControllerType::Jupiter: {
			outBuf[0] = 0xEA;
			outBuf[2] = (uint8_t)(!channel);
			outBuf[3] = 0x03;
			outBuf[5] = (uint8_t)(((g_directVel.load()) ? (velocity * 255) / 127 - 128 : 0x00) + g_gainModifier[!channel]);
			outBuf[6] = (uint8_t)(((int)frequency) % 0xFF);
			outBuf[7] = (uint8_t)(((int)frequency) / 0xFF);
			outBuf[8] = duration % 0xFF;
			outBuf[9] = duration / 0xFF;
			return 64;
		}
	}
	return 0;
}

static bool sendPacketToJava(JNIEnv* env, const uint8_t* data, int len) {
	std::lock_guard<std::mutex> lock(g_callbackMutex);
	if (g_callbackTarget == nullptr || g_sendPacketMethod == nullptr) return false;
	jbyteArray arr = env->NewByteArray(len);
	env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
	jboolean result = env->CallBooleanMethod(g_callbackTarget, g_sendPacketMethod, arr);
	env->DeleteLocalRef(arr);
	return result == JNI_TRUE;
}

static void notifyNotePlayed(JNIEnv* env, int channel, int note) {
	std::lock_guard<std::mutex> lock(g_callbackMutex);
	if (g_callbackTarget == nullptr || g_onNotePlayedMethod == nullptr) return;
	env->CallVoidMethod(g_callbackTarget, g_onNotePlayedMethod, channel, note);
}

static void notifyPlaybackFinished(JNIEnv* env) {
	std::lock_guard<std::mutex> lock(g_callbackMutex);
	if (g_callbackTarget == nullptr || g_onPlaybackFinishedMethod == nullptr) return;
	env->CallVoidMethod(g_callbackTarget, g_onPlaybackFinishedMethod);
}

static void stopAllChannels(JNIEnv* env, ControllerType type) {
	uint8_t buf[65];
	for (int i = 0; i < CHANNEL_COUNT; i++) {
		int len = buildHapticPacket(type, i, NOTE_STOP, 0, buf);
		sendPacketToJava(env, buf, len);
	}
}

extern "C" JNIEXPORT void JNICALL Java_com_balil_steamhaptics_HapticsNative_nativeSetCallbackTarget(JNIEnv* env, jclass /*clazz*/, jobject target) {
	std::lock_guard<std::mutex> lock(g_callbackMutex);
	if (g_callbackTarget != nullptr) env->DeleteGlobalRef(g_callbackTarget);
	g_callbackTarget = (target != nullptr) ? env->NewGlobalRef(target) : nullptr;
	if (g_callbackTarget != nullptr) {
		jclass cls = env->GetObjectClass(g_callbackTarget);
		g_sendPacketMethod = env->GetMethodID(cls, "sendPacket", "([B)Z");
		g_onNotePlayedMethod = env->GetMethodID(cls, "onNotePlayed", "(II)V");
		g_onPlaybackFinishedMethod = env->GetMethodID(cls, "onPlaybackFinished", "()V");
		env->DeleteLocalRef(cls);
	} else {
		g_sendPacketMethod = nullptr;
		g_onNotePlayedMethod = nullptr;
		g_onPlaybackFinishedMethod = nullptr;
	}
}

extern "C" JNIEXPORT void JNICALL Java_com_balil_steamhaptics_HapticsNative_nativeSetOptions(JNIEnv* /*env*/, jclass /*clazz*/, jboolean directVel, jboolean tritonLimit, jboolean tritonSwap, jboolean noGainCurve, jint lMod, jint rMod, jint nMod, jint mMod) {
	g_directVel.store(directVel == JNI_TRUE);
	g_tritonLimit.store(tritonLimit == JNI_TRUE);
	g_tritonSwap.store(tritonSwap == JNI_TRUE);
	g_noGainCurve.store(noGainCurve == JNI_TRUE);
	g_gainModifier[0] = lMod; g_gainModifier[1] = rMod; g_gainModifier[3] = nMod; g_gainModifier[4] = mMod;
}

extern "C" JNIEXPORT void JNICALL Java_com_balil_steamhaptics_HapticsNative_nativeLoadGainCurve(JNIEnv* env, jclass /*clazz*/, jintArray trackpadCurve, jintArray rumbleCurve) {
	if (g_noGainCurve.load()) return;
	jint* tp = env->GetIntArrayElements(trackpadCurve, nullptr);
	for (int i = 0; i < 128; i++) gainCurve[i] = (uint8_t)tp[i];
	env->ReleaseIntArrayElements(trackpadCurve, tp, JNI_ABORT);
	if (rumbleCurve != nullptr) {
		jint* rb = env->GetIntArrayElements(rumbleCurve, nullptr);
		for (int i = 0; i < 128; i++) gainCurveRb[i] = (uint8_t)rb[i];
		env->ReleaseIntArrayElements(rumbleCurve, rb, JNI_ABORT);
	}
}

extern "C" JNIEXPORT void JNICALL Java_com_balil_steamhaptics_HapticsNative_nativeRequestStop(JNIEnv* /*env*/, jclass /*clazz*/) {
	g_stopRequested.store(true);
}

extern "C" JNIEXPORT void JNICALL Java_com_balil_steamhaptics_HapticsNative_nativePlaySong(JNIEnv* env, jclass /*clazz*/, jstring midiFilePath, jint controllerTypeOrdinal, jint intervalUsec, jboolean repeat) {
	const char* pathConst = env->GetStringUTFChars(midiFilePath, nullptr);
	char* path = strdup(pathConst);
	env->ReleaseStringUTFChars(midiFilePath, pathConst);
	ControllerType type = static_cast<ControllerType>(controllerTypeOrdinal);
	g_channelCount = (type == ControllerType::Triton && !g_tritonLimit.load()) ? 4 : 2;
	LOGI("Loading MIDI: %s", path);
	MidiFile_t midifile = MidiFile_load(path);
	free(path);
	if (midifile == nullptr) {
		LOGE("Unable to open MIDI file");
		notifyPlaybackFinished(env);
		return;
	}
	if (MidiFile_getFirstEvent(midifile) == nullptr) {
		LOGE("MIDI file is empty");
		MidiFile_free(midifile);
		notifyPlaybackFinished(env);
		return;
	}
	g_stopRequested.store(false);
	LOGI("Playback started");
	do {
		MidiFileEvent_t acceptedEventPerChannel[CHANNEL_COUNT] = {nullptr};
		std::chrono::steady_clock::time_point tOrigin = std::chrono::steady_clock::now();
		MidiFileEvent_t currentEvent = MidiFile_getFirstEvent(midifile);
		while (currentEvent != nullptr && !g_stopRequested.load()) {
			std::this_thread::sleep_for(std::chrono::microseconds(intervalUsec));
			MidiFileEvent_t eventsToPlay[CHANNEL_COUNT] = {nullptr};
			double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - tOrigin).count();
			long currentTick = MidiFile_getTickFromTime(midifile, (float)elapsed);
			for (; currentEvent != nullptr && MidiFileEvent_getTick(currentEvent) < currentTick; currentEvent = MidiFileEvent_getNextEventInFile(currentEvent)) {
				if (!MidiFileEvent_isNoteStartEvent(currentEvent) && !MidiFileEvent_isNoteEndEvent(currentEvent)) continue;
				int eventChannel = MidiFileVoiceEvent_getChannel(currentEvent);
				if (eventChannel < 0 || !(eventChannel < g_channelCount)) continue;
				if (MidiFileEvent_isNoteEndEvent(currentEvent)) {
					MidiFileEvent_t previousEvent = acceptedEventPerChannel[eventChannel];
					if (previousEvent == nullptr || MidiFileNoteStartEvent_getNote(previousEvent) != MidiFileNoteEndEvent_getNote(currentEvent) || (MidiFileEvent_getTick(currentEvent) == MidiFileEvent_getTick(previousEvent))) continue;
				}
				eventsToPlay[eventChannel] = currentEvent;
				acceptedEventPerChannel[eventChannel] = currentEvent;
			}
			for (int ch = 0; ch < g_channelCount; ch++) {
				MidiFileEvent_t selectedEvent = eventsToPlay[ch];
				if (selectedEvent == nullptr) continue;
				int eventNote = NOTE_STOP;
				int eventVel = 0;
				if (MidiFileEvent_isNoteStartEvent(selectedEvent)) {
					uint8_t stopBuf[65];
					int stopLen = buildHapticPacket(type, ch, NOTE_STOP, 0, stopBuf);
					sendPacketToJava(env, stopBuf, stopLen);
					eventNote = MidiFileNoteStartEvent_getNote(selectedEvent);
					eventVel = MidiFileNoteStartEvent_getVelocity(selectedEvent);
				}
				uint8_t buf[65];
				int len = buildHapticPacket(type, ch, eventNote, eventVel, buf);
				if (!sendPacketToJava(env, buf, len)) {
					LOGE("USB packet send failed, stopping playback");
					g_stopRequested.store(true);
					break;
				}
				notifyNotePlayed(env, ch, eventNote);
			}
		}
		stopAllChannels(env, type);
	} while (repeat == JNI_TRUE && !g_stopRequested.load());
	MidiFile_free(midifile);
	LOGI("Playback finished");
	notifyPlaybackFinished(env);
}
