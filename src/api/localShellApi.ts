export interface LocalSetupState {
  version: number;
  activationMode: "tap" | "push";
  dictationHotkey: string;
  assistantHotkey: string;
  completed: boolean;
}

const STORAGE_KEY = "localSetupV1";

function readState(): LocalSetupState | null {
  if (typeof window === "undefined" || typeof localStorage === "undefined") return null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed?.version !== 1) return null;
    return parsed as LocalSetupState;
  } catch {
    return null;
  }
}

// Renderer-local reads/writes first; IPC variants call main when available.
export async function getLocalSetup(): Promise<LocalSetupState | null> {
  const local = readState();
  if (local) return local;
  try {
    const bridgeResult = await window.electronAPI?.getLocalSetup?.();
    if (bridgeResult && typeof bridgeResult === "object" && "version" in bridgeResult) {
      const normalized = bridgeResult as LocalSetupState;
      if (normalized.version === 1) {
        const clone: LocalSetupState = {
          version: normalized.version,
          activationMode: normalized.activationMode ?? "tap",
          dictationHotkey: String(normalized.dictationHotkey ?? ""),
          assistantHotkey: String(normalized.assistantHotkey ?? ""),
          completed: Boolean(normalized.completed),
        };
        if (clone.completed) {
          saveLocalSetup(clone);
        }
        return clone;
      }
    }
    return null;
  } catch {
    return null;
  }
}

export async function saveLocalSetup(state: LocalSetupState): Promise<boolean> {
  if (typeof window === "undefined" || typeof localStorage === "undefined") {
    return false;
  }
  try {
    const normalized: LocalSetupState = {
      version: 1,
      activationMode: state.activationMode ?? "tap",
      dictationHotkey: String(state.dictationHotkey ?? ""),
      assistantHotkey: String(state.assistantHotkey ?? ""),
      completed: Boolean(state.completed),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
    try {
      const mainOk = await window.electronAPI?.saveLocalSetup?.(normalized);
      if (mainOk !== false && mainOk !== null) {
        return Boolean(mainOk);
      }
    } catch {
      // Local storage is the authoritative fallback for setup completion.
    }
    return true;
  } catch {
    return false;
  }
}

// Forward hotkey/activation persistence to the main process when present.
export async function updateHotkey(hotkey: string): Promise<boolean> {
  try {
    const result = await window.electronAPI?.updateHotkey?.(hotkey);
    return Boolean(result?.success !== false);
  } catch {
    return false;
  }
}

export async function getEffectiveDefaultHotkey(): Promise<string | null> {
  try {
    const key = await window.electronAPI?.getEffectiveDefaultHotkey?.();
    return typeof key === "string" ? key : null;
  } catch {
    return null;
  }
}

export async function getHotkeyModeInfo(hotkey: string): Promise<{ supportsPushToTalk?: boolean } | null> {
  try {
    const info = await window.electronAPI?.getHotkeyModeInfo?.(hotkey);
    if (info && typeof info === "object") return info as { supportsPushToTalk?: boolean };
    return null;
  } catch {
    return null;
  }
}

export async function canRegisterHotkey(hotkey: string): Promise<boolean> {
  try {
    const result = await window.electronAPI?.canRegisterHotkey?.(hotkey);
    return Boolean(result?.success !== false);
  } catch {
    return false;
  }
}
