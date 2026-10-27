/*
 * libbark_asr.so — the JNI seam between dev.barkboard.ime.asr.BarkAsr (Kotlin) and the
 * sherpa-onnx C API.
 *
 * The Kotlin side owns everything around it: model files are downloaded and SHA-256 verified
 * before this library is ever loaded, and audio arrives as 16 kHz mono floats exactly as
 * captured. This file therefore does no I/O, no verification and no text munging — it maps three
 * calls onto one recognizer handle.
 *
 * Built against sherpa-onnx v2.0.1 (see docs/native-engine.md for how the prebuilts are fetched).
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "c-api.h"

#define PATH_CAP 4096
#define SAMPLE_RATE 16000
#define FEATURE_DIM 80

/* Bark 3.0 Clean is a Parakeet-family export; sherpa-onnx needs to be told, since the transducer
 * graphs alone do not identify the family. Greedy search is all a TDT decoder needs. */
#define MODEL_TYPE "nemo_transducer"
#define DECODING_METHOD "greedy_search"

static int join_path(char *out, size_t cap, const char *dir, const char *name) {
  int written = snprintf(out, cap, "%s/%s", dir, name);
  return written > 0 && (size_t)written < cap;
}

JNIEXPORT jlong JNICALL
Java_dev_barkboard_ime_asr_BarkAsr_barkAsrCreate(JNIEnv *env, jclass clazz, jstring model_dir,
                                                jint threads) {
  (void)clazz;
  if (model_dir == NULL) return 0;

  const char *dir = (*env)->GetStringUTFChars(env, model_dir, NULL);
  if (dir == NULL) return 0; /* exception pending: out of memory */

  char encoder[PATH_CAP];
  char decoder[PATH_CAP];
  char joiner[PATH_CAP];
  char tokens[PATH_CAP];

  const int paths_ok = join_path(encoder, sizeof(encoder), dir, "encoder.int8.onnx") &&
                       join_path(decoder, sizeof(decoder), dir, "decoder.int8.onnx") &&
                       join_path(joiner, sizeof(joiner), dir, "joiner.int8.onnx") &&
                       join_path(tokens, sizeof(tokens), dir, "tokens.txt");

  const SherpaOnnxOfflineRecognizer *recognizer = NULL;
  if (paths_ok) {
    SherpaOnnxOfflineRecognizerConfig config;
    memset(&config, 0, sizeof(config));

    config.feat_config.sample_rate = SAMPLE_RATE;
    config.feat_config.feature_dim = FEATURE_DIM;

    config.model_config.transducer.encoder = encoder;
    config.model_config.transducer.decoder = decoder;
    config.model_config.transducer.joiner = joiner;
    config.model_config.tokens = tokens;
    config.model_config.num_threads = (int32_t)threads;
    config.model_config.provider = "cpu";
    config.model_config.model_type = MODEL_TYPE;

    config.decoding_method = DECODING_METHOD;

    recognizer = SherpaOnnxCreateOfflineRecognizer(&config);
  }

  (*env)->ReleaseStringUTFChars(env, model_dir, dir);

  /* A 0 handle is the documented "could not load" signal; Kotlin turns it into the
   * model-unavailable path instead of throwing. */
  return (jlong)(intptr_t)recognizer;
}

JNIEXPORT jstring JNICALL
Java_dev_barkboard_ime_asr_BarkAsr_barkAsrTranscribe(JNIEnv *env, jclass clazz, jlong handle,
                                                    jfloatArray samples) {
  (void)clazz;
  const SherpaOnnxOfflineRecognizer *recognizer = (const SherpaOnnxOfflineRecognizer *)(intptr_t)handle;
  if (recognizer == NULL) return (*env)->NewStringUTF(env, ""); /* never a null String in Kotlin */

  const jsize count = samples == NULL ? 0 : (*env)->GetArrayLength(env, samples);
  if (count <= 0) return (*env)->NewStringUTF(env, ""); /* silence is a valid result */

  jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);
  if (pcm == NULL) return (*env)->NewStringUTF(env, "");

  const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(recognizer);
  if (stream == NULL) {
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    return (*env)->NewStringUTF(env, "");
  }

  SherpaOnnxAcceptWaveformOffline(stream, SAMPLE_RATE, pcm, (int32_t)count);
  (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);

  SherpaOnnxDecodeOfflineStream(recognizer, stream);

  const SherpaOnnxOfflineRecognizerResult *result = SherpaOnnxGetOfflineStreamResult(stream);
  jstring text = NULL;
  if (result != NULL) {
    if (result->text != NULL) text = (*env)->NewStringUTF(env, result->text);
    SherpaOnnxDestroyOfflineRecognizerResult(result);
  }

  SherpaOnnxDestroyOfflineStream(stream);

  /* Kotlin never sees a null text: a decode that produced nothing reads as silence. */
  return text != NULL ? text : (*env)->NewStringUTF(env, "");
}

JNIEXPORT void JNICALL
Java_dev_barkboard_ime_asr_BarkAsr_barkAsrDestroy(JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  const SherpaOnnxOfflineRecognizer *recognizer = (const SherpaOnnxOfflineRecognizer *)(intptr_t)handle;
  if (recognizer != NULL) SherpaOnnxDestroyOfflineRecognizer(recognizer);
}
