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

async function downloadParakeet(
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  return window.electronAPI?.downloadParakeetModel?.(modelName).then((result: any) => {
      if (result && typeof result === "object") {
        const ok = Boolean(result.success !== false);
        return {
          success: ok,
          model: result.model ?? modelName,
          error: ok ? undefined : String(result.error ?? result.message ?? "parakeet_download_failed"),
        };
      }
      if (typeof result === "string" && result.trim()) {
        return { success: true, model: modelName };
      }
      return { success: true, model: modelName };
    }).catch((error: any) => ({
    success: false,
    error: normalizeError(error),
  }));
}

async function downloadWhisper(
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  return window.electronAPI?.downloadWhisperModel?.(modelName).then((result: any) => {
    if (result && typeof result === "object") {
      const ok = Boolean(result.success !== false);
      return {
        success: ok,
        model: result.model ?? modelName,
        error: ok ? undefined : String(result.error ?? result.message ?? "whisper_download_failed"),
      };
    }
    if (typeof result === "string" && result.trim()) {
      return { success: true, model: modelName };
    }
    return { success: true, model: modelName };
  }).catch((error: any) => ({
    success: false,
    error: normalizeError(error),
  }));
}

async function downloadUnknown(
  modelName: string,
  onProgress?: (data: ModelDownloadProgress) => void,
): Promise<ModelDownloadResult> {
  try {
    const result = await window.electronAPI?.modelDownload?.(modelName);
    if (result && typeof result === "object") {
      const ok = Boolean(result.success !== false);
      return {
        success: ok,
        model: result.model ?? modelName,
        error: ok ? undefined : String(result.error ?? result.message ?? "model_download_failed"),
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
