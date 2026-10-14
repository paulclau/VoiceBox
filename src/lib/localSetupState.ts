export type DownloadPhase = "progress" | "installing" | "complete" | "failed";

export interface LocalSetupItem {
  modelId: string;
  label: string;
  description?: string;
  sizeLabel?: string;
}

export interface LocalSetupProgressEntry {
  modelId: string;
  phase: DownloadPhase;
  percentage: number;
  downloadedBytes?: number;
  totalBytes?: number;
  error?: string;
}

export interface LocalSetupProgress {
  active: LocalSetupProgressEntry[];
  done: LocalSetupProgressEntry[];
  failed: LocalSetupProgressEntry[];
  startedAt?: number;
}

export function isComplete(progress: LocalSetupProgress | null, items: LocalSetupItem[]): boolean {
  if (!progress) return false;
  if (progress.active.length > 0) return false;
  if (progress.failed.length > 0) return false;
  return progress.done.length >= items.length;
}

export function overallPercent(progress: LocalSetupProgress | null, items: LocalSetupItem[]): number {
  if (!progress) return 0;
  if (progress.failed.length > 0) return 0;
  if (progress.done.length >= items.length) return 100;
  if (progress.active.length === 0) return 0;
  const sum = progress.active.reduce((s, a) => s + (a.percentage || 0), 0);
  return Math.round(sum / progress.active.length);
}
