import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useAudioRecording } from "./useAudioRecording";
import type { LocalTranscriptionResult } from "../api/sttApi";

interface UseMicCaptureOptions {
  onResult?: (result: LocalTranscriptionResult) => void;
  onError?: (error: string) => void;
}

export function useMicCapture(options: UseMicCaptureOptions = {}) {
  const { t } = useTranslation();
  const [started, setStarted] = useState(false);
  const [lastError, setLastError] = useState<string | null>(null);
  const firedRef = useRef(false);
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const recording = useAudioRecording(
    (toast) => {
      // The onboarding step owns its own toast surface if it wants one; the mic
      // capture path itself stays quiet here and surfaces errors through the hook.
    },
    {
      onDemoEvent: (event) => {
        if (event.status === "listening" && !firedRef.current) {
          setStarted(true);
          firedRef.current = true;
        }
      },
      onDictationError: (payload) => {
        const error =
          payload?.message ??
          payload?.code ??
          (t("hooks.audioRecording.micDisconnected.description") ?? "mic_capture_failed");
        setLastError(String(error));
        optionsRef.current.onError?.(String(error));
      },
      onTranscriptionComplete: (result) => {
        if (!result?.success) {
          const error =
            result?.error ??
            result?.message ??
            (t("hooks.audioRecording.noAudio.description") ?? "local_transcription_failed");
          setLastError(String(error));
          optionsRef.current.onError?.(String(error));
          return;
        }
        const value: LocalTranscriptionResult =
          result.text.trim()
            ? { ok: true, text: result.text.trim() }
            : { ok: false, error: t("hooks.audioRecording.noAudio.description") ?? "parakeet_empty" };
        setLastError(value.ok ? null : value.error);
        optionsRef.current.onResult?.(value);
      },
    },
  );

  const start = useCallback(async () => {
    firedRef.current = false;
    setLastError(null);
    setStarted(false);
    const startedOk = await recording.startRecording();
    if (!startedOk && !recording.isRecording && !recording.isProcessing) {
      setLastError(t("hooks.audioRecording.micDisconnected.description") ?? "mic_capture_failed");
      optionsRef.current.onError?.(t("hooks.audioRecording.micDisconnected.description") ?? "mic_capture_failed");
    }
  }, [recording, t]);

  const stop = useCallback(async () => {
    const ok = await recording.stopRecording();
    if (!ok && !recording.isRecording && !recording.isProcessing) {
      setLastError(t("hooks.audioRecording.micDisconnected.description") ?? "mic_capture_failed");
    }
  }, [recording, t]);

  const transcribe = useCallback(async () => {
    // The real AudioManager transcribes on stop via the existing recording path,
    // so an explicit transcribe call is a no-op here; the result arrives through
    // onTranscriptionComplete. Keep the API shape in case a future step wants an
    // explicit finalize trigger.
    return recording.isRecording || recording.isProcessing;
  }, [recording]);

  useEffect(() => {
    return () => {
      if (recording.isRecording || recording.isProcessing) {
        recording.stopRecording().catch(() => {});
      }
    };
  }, [recording]);

  return {
    recording: recording.isRecording || recording.isProcessing || started,
    transcribing: recording.isProcessing,
    result: recording.transcript.trim() ? { ok: true, text: recording.transcript.trim() } : null,
    error: lastError ?? undefined,
    start,
    stop,
    transcribe,
    cancel: recording.cancelRecording,
  };
}
