import { getLocalSetup, saveLocalSetup, updateHotkey, getEffectiveDefaultHotkey, getHotkeyModeInfo, canRegisterHotkey } from "../api/localShellApi";
import { downloadModel, type ModelDownloadProgress, type ModelDownloadResult } from "../api/modelDownloadsApi";
import { transcribeLocalAudio, type LocalTranscriptionResult } from "../api/sttApi";

export type { LocalSetupState } from "../api/localShellApi";
export { getLocalSetup, saveLocalSetup } from "../api/localShellApi";
export { downloadModel, type ModelDownloadProgress, type ModelDownloadResult } from "../api/modelDownloadsApi";
export { transcribeLocalAudio, type LocalTranscriptionResult } from "../api/sttApi";
