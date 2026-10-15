export type ModelDownloadProgress = {
  type: "progress" | "installing" | "complete";
  model: string;
  percentage: number;
  downloadedBytes?: number;
  totalBytes?: number;
};

export type ModelDownloadResult = {
  success: boolean;
  model?: string;
  error?: string;
};

// Map our normalized model id to the existing catalog / provider names.
function resolveModelName(modelId: string): string {
  if (modelId.startsWith("whisper:")) return modelId.slice("whisper:".length);
  return modelId;
}

export async function downloadModel(
  modelId: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  const resolved = resolveModelName(modelId);

  if (isParakeetModel(resolved)) {
    return downloadParakeet(resolved, onProgress);
  }

  if (isWhisperModel(resolved)) {
    return downloadWhisper(resolved, onProgress);
  }

  // Fallback: route to a generic model download if present.
  return downloadUnknown(resolved, onProgress);
}

function toProgress(modelName: string, data: any): ModelDownloadProgress {
  const raw = typeof data?.percentage === "number"
    ? data.percentage
    : typeof data?.downloadProgress === "number"
      ? data.downloadProgress
      : typeof data?.progress === "number"
        ? data.progress
        : 0;
  const type = data?.type === "installing" || data?.isInstalling ? "installing" : "progress";
  return {
    type,
    model: typeof data?.model === "string" ? data.model : modelName,
    percentage: raw,
    downloadedBytes: data?.downloadedBytes ?? data?.downloaded_bytes,
    totalBytes: data?.totalBytes ?? data?.total_bytes,
  };
}

function subscribeProgress(
  subscribe: ((cb: (event: any, data: any) => void) => () => void) | undefined,
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): (() => void) | undefined {
  if (!subscribe || !onProgress) return undefined;
  try {
    return subscribe((_event: any, data: any) => {
      if (!data || (data.model && data.model !== modelName)) return;
      onProgress(toProgress(modelName, data));
    });
  } catch {
    return undefined;
  }
}

async function downloadParakeet(
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  const api = (window as any).electronAPI;
  if (!api?.downloadParakeetModel) return { success: true, model: modelName };
  const unsubscribe = subscribeProgress(api.onParakeetDownloadProgress?.bind(api), modelName, onProgress);
  try {
    const result = await api.downloadParakeetModel(modelName);
    if (result && typeof result === "object") {
      const ok = Boolean(result.success !== false);
      return {
        success: ok,
        model: typeof result.model === "string" ? result.model : modelName,
        error: ok ? undefined : String(result.error ?? "parakeet_download_failed"),
      };
    }
    if (typeof result === "string" && result.trim()) {
      return { success: true, model: modelName };
    }
    return { success: true, model: modelName };
  } catch (error: any) {
    return {
      success: false,
      error: normalizeError(error),
    };
  } finally {
    try { unsubscribe?.(); } catch { /* noop */ }
  }
}

async function downloadWhisper(
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  const api = (window as any).electronAPI;
  if (!api?.downloadWhisperModel) return { success: true, model: modelName };
  const unsubscribe = subscribeProgress(api.onWhisperDownloadProgress?.bind(api), modelName, onProgress);
  try {
    const result = await api.downloadWhisperModel(modelName);
    if (result && typeof result === "object") {
      const ok = Boolean(result.success !== false);
      return {
        success: ok,
        model: typeof result.model === "string" ? result.model : modelName,
        error: ok ? undefined : String(result.error ?? "whisper_download_failed"),
      };
    }
    if (typeof result === "string" && result.trim()) {
      return { success: true, model: modelName };
    }
    return { success: true, model: modelName };
  } catch (error: any) {
    return {
      success: false,
      error: normalizeError(error),
    };
  } finally {
    try { unsubscribe?.(); } catch { /* noop */ }
  }
}

async function downloadUnknown(
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  try {
    const api = (window as any).electronAPI;
    const result = await api?.modelDownload?.(modelName);
    if (result && typeof result === "object") {
      const ok = Boolean(result.success !== false);
      return {
        success: ok,
        model: typeof result.model === "string" ? result.model : (result.path ?? modelName),
        error: ok ? undefined : String(result.error ?? "model_download_failed"),
      };
    }
    return { success: true, model: modelName };
  } catch (error) {
    return { success: false, error: normalizeError(error) };
  }
}

function isParakeetModel(modelId: string): boolean {
  return /^parakeet|nemotron|cohere|orukeet/i.test(modelId);
}

function isWhisperModel(modelId: string): boolean {
  return /^(tiny|base|small|medium|large|turbo)$/.test(modelId);
}

function normalizeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    const parsed = JSON.parse(JSON.stringify(error));
    if (parsed?.message) return String(parsed.message);
    if (parsed?.error) return String(parsed.error);
    return JSON.stringify(parsed);
  } catch {
    return "download_failed";
  }
}
