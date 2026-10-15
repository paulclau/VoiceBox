import { useCallback, useEffect, useRef, useState } from "react";
import type { LocalSetupItem, LocalSetupProgress, LocalSetupProgressEntry } from "../lib/localSetupState";
import { downloadModel } from "../api/modelDownloadsApi";

interface UseDownloadCoordinatorOptions {
  items: LocalSetupItem[];
  onStart?: () => void;
  onCancel?: () => void;
  onProgress?: (progress: LocalSetupProgress) => void;
}

function entry(modelId: string): LocalSetupProgressEntry {
  return { modelId, phase: "progress", percentage: 0 };
}

export function useDownloadCoordinator({
  items,
  onStart,
  onCancel,
  onProgress,
}: UseDownloadCoordinatorOptions) {
  const [progress, setProgress] = useState<LocalSetupProgress | null>(null);
  const cancelRef = useRef(false);
  const onProgressRef = useRef(onProgress);
  onProgressRef.current = onProgress;

  const runSingle = useCallback(async (modelId: string) => {
    if (cancelRef.current) return;

    // Normalise model ID so it can be matched to the IPC model catalog.
    const resolvedModelId = modelId.startsWith("whisper:")
      ? modelId.slice("whisper:".length)
      : modelId;

    try {
      setProgress((prev) => {
        if (!prev) return prev;
        const active = prev.active.find((a) => a.modelId === modelId);
        if (!active) return prev;
        const next: LocalSetupProgressEntry = { ...active, phase: "progress", percentage: 0 };
        return { ...prev, active: prev.active.map((a) => (a.modelId === modelId ? next : a)) };
      });

      const result = await downloadModel(resolvedModelId, (data) => {
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
          return { ...prev, active: prev.active.map((a) => (a.modelId === modelId ? next : a)) };
        });
      });

      if (cancelRef.current) return;

      if (!result.success) {
        throw new Error(result.error ?? "download_failed");
      }

      // Mark complete only after the underlying download helper resolved.
      setProgress((prev) => {
        if (!prev) return prev;
        if (prev.failed.some((f) => f.modelId === modelId)) return prev;
        if (prev.done.some((d) => d.modelId === modelId)) return prev;
        const active = prev.active.find((a) => a.modelId === modelId);
        if (!active) return prev;
        const next: LocalSetupProgressEntry = { ...active, phase: "complete", percentage: 100 };
        return {
          ...prev,
          active: prev.active.filter((a) => a.modelId !== modelId),
          done: [...prev.done, next],
        };
      });
    } catch (error) {
      if (cancelRef.current) return;
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
        return {
          ...prev,
          active: prev.active.filter((a) => a.modelId !== modelId),
          failed: [...prev.failed, next],
        };
      });
    }
  }, []);

  const start = useCallback(async () => {
    const list = items ?? [];
    cancelRef.current = false;
    setProgress({
      active: list.map((item) => entry(item.modelId)),
      done: [],
      failed: [],
      startedAt: Date.now(),
    });
    onStart?.();
    // Drive the loop from the item list directly — reading `progress` state
    // here would see the pre-start snapshot (stale closure) and no-op.
    for (const item of list) {
      if (cancelRef.current) break;
      await runSingle(item.modelId);
    }
  }, [items, onStart, runSingle]);

  const cancel = useCallback(() => {
    cancelRef.current = true;
    // Reset to idle so the UI offers Download again instead of hanging on a
    // stale percentage. Items never started must not be reported as failed,
    // so everything goes back to the pre-start list (a later start() retries).
    setProgress({ active: [], done: [], failed: [] });
    onCancel?.();
  }, [onCancel]);

  // Report real snapshots to the owner. This lives in an effect — never
  // inside a state updater, which must stay pure (StrictMode double-invokes).
  useEffect(() => {
    if (progress) onProgressRef.current?.(progress);
  }, [progress]);

  return { progress, start, cancel };
}

function normalizeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    const parsed = JSON.parse(JSON.stringify(error));
    if (parsed?.message) return String(parsed.message);
    if (parsed?.error) return String(parsed.error);
  } catch { /* noop */ }
  return "download_failed";
}
