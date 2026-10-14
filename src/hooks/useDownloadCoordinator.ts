import { useCallback, useEffect, useRef, useState } from "react";
import type { LocalSetupItem, LocalSetupProgress, LocalSetupProgressEntry, DownloadPhase } from "../lib/localSetupState";
import { downloadModel } from "../api/modelDownloadsApi";

interface UseDownloadCoordinatorOptions {
  items: LocalSetupItem[];
  onStart?: () => void;
  onCancel?: () => void;
  onProgress?: (progress: LocalSetupProgress) => void;
}

export function useDownloadCoordinator({
  items,
  onStart,
  onCancel,
  onProgress,
}: UseDownloadCoordinatorOptions) {
  const [progress, setProgress] = useState<LocalSetupProgress | null>(null);
  const cancelRef = useRef(false);

  const normalizedItems = items ?? [];

  const start = useCallback(async () => {
    cancelRef.current = false;
    const initial: LocalSetupProgress = {
      active: normalizedItems.map((item) => ({
        modelId: item.modelId,
        phase: "progress",
        percentage: 0,
      })),
      done: [],
      failed: [],
      startedAt: Date.now(),
    };
    setProgress(initial);
    onStart?.();
    await runDownloads();
  }, [normalizedItems, onStart]);

  const runDownloads = useCallback(async () => {
    if (!progress) return;
    for (const item of normalizedItems) {
      if (cancelRef.current) break;
      const existing = progress.done.find((d) => d.modelId === item.modelId);
      if (existing) continue;
      const failed = progress.failed.find((f) => f.modelId === item.modelId);
      if (failed) continue;

      await runSingle(item.modelId, item);
    }
  }, [progress, normalizedItems]);

  const runSingle = useCallback(
    async (modelId: string, item: LocalSetupItem) => {
      if (cancelRef.current) return;

      // Normalise model ID so it can be matched to the IPC model catalog.
      const resolvedModelId = modelId.startsWith("whisper:")
        ? modelId.slice("whisper:".length)
        : modelId;

      try {
        await setProgress((prev) => {
          if (!prev) return prev;
          const active = prev.active.find((a) => a.modelId === modelId);
          if (!active) return prev;
          const next: LocalSetupProgressEntry = {
            ...active,
            phase: "progress",
            percentage: 0,
          };
          const active2 = prev.active.map((a) => (a.modelId === modelId ? next : a));
          return { ...prev, active: active2 };
        });

        await downloadModel(resolvedModelId, (data) => {
          if (cancelRef.current) return;
          setProgress((prev) => {
            if (!prev) return prev;
            const active = prev.active.find((a) => a.modelId === modelId);
            if (!active || active.phase === "complete" || active.phase === "failed") return prev;
            const next: LocalSetupProgressEntry = {
              ...active,
              phase: data.type === "installing" ? "installing" : "progress",
              percentage: data.percentage ?? 0,
              downloadedBytes: data.downloadedBytes,
              totalBytes: data.totalBytes,
            };
            const active2 = prev.active.map((a) => (a.modelId === modelId ? next : a));
            return { ...prev, active: active2 };
          });
        });

        if (cancelRef.current) return;

        // Mark complete only after the underlying download helper resolved.
        // The helper may already have reported 100% during install, but we
        // keep the done state authoritative here.
        setProgress((prev) => {
          if (!prev) return prev;
          const failedEntry = prev.failed.find((f) => f.modelId === modelId);
          const doneEntry = prev.done.find((d) => d.modelId === modelId);
          if (failedEntry || doneEntry) return prev;
          const active = prev.active.find((a) => a.modelId === modelId);
          if (!active) return prev;
          const next: LocalSetupProgressEntry = {
            ...active,
            phase: "complete",
            percentage: 100,
          };
          const active2 = prev.active.filter((a) => a.modelId !== modelId);
          const done = [...prev.done, next];
          return { ...prev, active: active2, done };
        });

        onProgress?.({ active: [], done: [], failed: [] });
      } catch (error) {
        const normalized = normalizeError(error);
        setProgress((prev) => {
          if (!prev) return prev;
          const active = prev.active.find((a) => a.modelId === modelId);
          if (!active) return prev;
          const next: LocalSetupProgressEntry = {
            ...active,
            phase: "failed",
            percentage: 0,
            error: normalized,
          };
          const active2 = prev.active.filter((a) => a.modelId !== modelId);
          const failed = [...prev.failed, next];
          return { ...prev, active: active2, failed };
        });
      }
    },
    [onProgress],
  );

  const cancel = useCallback(() => {
    cancelRef.current = true;
    onCancel?.();
  }, [onCancel]);

  useEffect(() => {
    setProgress((prev) => {
      if (!prev) return prev;
      const doneCount = prev.done.length;
      const failedCount = prev.failed.length;
      if (doneCount + failedCount === normalizedItems.length && normalizedItems.length > 0) {
        onProgress?.(prev);
      }
      return prev;
    });
  }, [progress, normalizedItems, onProgress]);

  return { progress, start, cancel, onProgress: setProgress };
}

function normalizeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    const parsed = JSON.parse(JSON.stringify(error));
    if (parsed?.message) return String(parsed.message);
    if (parsed?.error) return String(parsed.error);
  } catch {}
  return "download_failed";
}
