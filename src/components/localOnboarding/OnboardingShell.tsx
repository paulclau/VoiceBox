import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Loader2 } from "../icons";
import { StepHeader } from "./StepHeader";
import { StepProgress } from "./StepProgress";
import DetectionStep from "./DetectionStep";
import LanguageStep from "./LanguageStep";
import RecommendedStackStep from "./RecommendedStackStep";
import DownloadStep from "./DownloadStep";
import MicTestStep from "./MicTestStep";
import ActivationStep from "./ActivationStep";
import { buildHardwareProfile, type HardwareProfile } from "../../lib/hardwareProfile";
import { getRecommendedLocalStack } from "../../lib/recommendedLocalStack";
import type { LocalStackRecommendation } from "../../lib/recommendedLocalStack";
import type { LocalSetupItem, LocalSetupProgress } from "../../lib/localSetupState";
import { isComplete } from "../../lib/localSetupState";
import { defaultLanguages, type SpokenLanguage } from "../../lib/localLanguages";
import type { LocalSetupState } from "../../api/localShellApi";
import { getLocalSetup, saveLocalSetup, canRegisterHotkey } from "../../api/localShellApi";
import { useMicCapture } from "../../hooks/useMicCapture";
import { useSettingsStore } from "../../stores/settingsStore";

export type SetupStepId =
  | "detection"
  | "languages"
  | "stack"
  | "download"
  | "micTest"
  | "activation";

export interface ActivationChoice {
  dictationMode: "tap" | "push";
  dictationHotkey: string;
  assistantHotkey: string;
  assistantEnabled: boolean;
}

interface Props {
  onComplete: () => void;
}

const ALL_STEPS: SetupStepId[] = ["detection", "languages", "stack", "download", "micTest", "activation"];

function normalizeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    const parsed = JSON.parse(JSON.stringify(error));
    if (parsed?.message) return String(parsed.message);
    if (parsed?.error) return String(parsed.error);
  } catch { /* noop */ }
  return "setup_error";
}

function readStoredLanguage(keys: string[]): string | null {
  try {
    for (const key of keys) {
      const raw = localStorage.getItem(key);
      if (!raw) continue;
      try {
        const parsed = JSON.parse(raw);
        if (typeof parsed === "string" && parsed.trim()) return parsed.trim();
      } catch {
        if (raw.trim()) return raw.trim();
      }
    }
  } catch { /* noop */ }
  return null;
}

function defaultSpokenLanguages(): SpokenLanguage[] {
  const candidates: string[] = [];
  try {
    const stored = readStoredLanguage(["preferredLanguage", "transcriptionLanguage", "language", "uiLanguage"]);
    // preferredLanguage defaults to "auto" (detect automatically) — that is
    // not a language, so fall through to i18n / the browser locale instead.
    if (stored && stored.toLowerCase() !== "auto") candidates.push(stored);
    const i18nLang = (window as any)?.i18n?.resolvedLanguage;
    if (typeof i18nLang === "string") candidates.push(i18nLang);
    if (navigator.language) candidates.push(navigator.language);
  } catch { /* noop */ }
  const base = candidates.length > 0 ? candidates[0] : "en";
  const short = base.split("-")[0].toLowerCase();
  const match = defaultLanguages.find((l) => l.code.toLowerCase() === base.toLowerCase())
    ?? defaultLanguages.find((l) => l.code.toLowerCase() === short)
    ?? defaultLanguages[0];
  return match ? [match] : [];
}

async function probeGpuRuntime(profile: HardwareProfile): Promise<HardwareProfile> {
  try {
    const api = (window as any).electronAPI;
    const gpu = await api?.detectGpu?.();
    const hasGpu = Boolean(gpu && (gpu.cudaAvailable || gpu.devices?.length > 0 || gpu.available));
    if (hasGpu) {
      return { ...profile, signals: { ...profile.signals, hasGpuRuntime: true } };
    }
  } catch { /* keep defaults */ }
  return profile;
}

async function fetchDownloadedIds(): Promise<Set<string>> {
  const downloaded = new Set<string>();
  try {
    const api = (window as any).electronAPI;
    if (!api) return downloaded;
    const [parakeetList, llmModels] = await Promise.allSettled([
      api.listParakeetModels?.(),
      api.modelGetAll?.(),
    ]);
    if (parakeetList.status === "fulfilled") {
      const models = (parakeetList.value as any)?.models ?? [];
      for (const m of models) {
        if (m?.downloaded && typeof m?.model === "string") downloaded.add(m.model);
      }
    }
    if (llmModels.status === "fulfilled" && Array.isArray(llmModels.value)) {
      for (const m of llmModels.value as any[]) {
        if ((m?.downloaded || m?.available || m?.installed) && typeof m?.id === "string") {
          downloaded.add(m.id);
        }
      }
    }
    // Whisper: check the two small candidates we may recommend.
    for (const id of ["base", "small"]) {
      try {
        const status = await api.checkModelStatus?.(id) ?? await api.checkWhisperModelStatus?.(id);
        if (status?.downloaded) downloaded.add(`whisper:${id}`);
      } catch { /* noop */ }
    }
  } catch { /* noop */ }
  return downloaded;
}

export default function OnboardingShell({ onComplete }: Props) {
  const { t } = useTranslation();

  const [step, setStep] = useState<SetupStepId>("detection");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [profile, setProfile] = useState<HardwareProfile | null>(null);
  const [languages, setLanguages] = useState<SpokenLanguage[]>(() => defaultSpokenLanguages());
  const [recommendation, setRecommendation] = useState<LocalStackRecommendation | null>(null);
  const [stackLoading, setStackLoading] = useState(false);
  const [stackError, setStackError] = useState<string | null>(null);
  const [stackChoice, setStackChoice] = useState<LocalStackRecommendation["choice"] | null>(null);
  const [downloadSummary, setDownloadSummary] = useState<LocalSetupItem[]>([]);
  const [downloadProgress, setDownloadProgress] = useState<LocalSetupProgress | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [activationLoading, setActivationLoading] = useState(true);
  const [activation, setActivation] = useState<LocalSetupState | null>(null);
  const [canRegister, setCanRegister] = useState(false);
  const [savingActivation, setSavingActivation] = useState(false);

  const doneRef = useRef(false);

  const detect = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      let p = buildHardwareProfile();
      p = await probeGpuRuntime(p);
      // deviceMemory is Chrome-only; upgrade RAM hint when main reports more.
      try {
        const api = (window as any).electronAPI;
        const sysInfo = await api?.getSystemInfo?.();
        if (typeof sysInfo?.totalMemoryGb === "number") {
          const ramHint = sysInfo.totalMemoryGb <= 8 ? "low" : sysInfo.totalMemoryGb >= 32 ? "enthusiast" : "mainstream";
          p = { ...p, class: ramHint, signals: { ...p.signals, ramHint } };
        }
      } catch { /* noop */ }
      setProfile(p);
    } catch (e) {
      setError(normalizeError(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const buildStack = useCallback(async () => {
    if (!profile) return;
    setStackLoading(true);
    setStackError(null);
    try {
      const langCodes = languages.map((l) => l.code);
      const wantStreaming = profile.signals.hasStreamingRuntime;
      const stack = getRecommendedLocalStack(profile.platform, profile.class, langCodes, wantStreaming);
      // Annotate with real on-disk availability and prefer downloaded models.
      const downloaded = await fetchDownloadedIds();
      const mark = <T extends { modelId: string }>(m: T): T & { downloaded: boolean } => ({
        ...m,
        downloaded: downloaded.has(m.modelId) || downloaded.has(m.modelId.replace(/^whisper:/, "")),
      });
      const transcriptionOptions = stack.transcriptionOptions.map(mark);
      const assistantOptions = stack.assistantOptions.map(mark);
      const preferDownloaded = <T extends { modelId: string; downloaded?: boolean }>(list: T[]): T[] =>
        [...list].sort((a, b) => Number(b.downloaded ?? false) - Number(a.downloaded ?? false));
      const orderedTranscription = preferDownloaded(transcriptionOptions);
      const orderedAssistant = preferDownloaded(assistantOptions);
      const next: LocalStackRecommendation = {
        ...stack,
        transcriptionOptions: orderedTranscription,
        assistantOptions: orderedAssistant,
        choice: {
          transcriptionModelId: orderedTranscription[0]?.modelId ?? stack.choice.transcriptionModelId,
          assistantModelId: orderedAssistant[0]?.modelId ?? stack.choice.assistantModelId,
        },
      };
      setRecommendation(next);
      setStackChoice(next.choice);
      const items: LocalSetupItem[] = [];
      const tModel = orderedTranscription.find((m) => m.modelId === next.choice.transcriptionModelId);
      if (next.choice.transcriptionModelId && !tModel?.downloaded) {
        items.push({
          modelId: next.choice.transcriptionModelId,
          label: tModel?.displayName ?? next.choice.transcriptionModelId,
          description: tModel?.description ?? "",
          sizeLabel: tModel?.sizeLabel ?? "",
        });
      }
      const aModel = orderedAssistant.find((m) => m.modelId === next.choice.assistantModelId);
      if (next.choice.assistantModelId && !aModel?.downloaded) {
        items.push({
          modelId: next.choice.assistantModelId,
          label: aModel?.displayName ?? next.choice.assistantModelId,
          description: aModel?.description ?? "",
          sizeLabel: aModel?.sizeLabel ?? "",
        });
      }
      setDownloadSummary(items);
    } catch (e) {
      setStackError(normalizeError(e));
    } finally {
      setStackLoading(false);
    }
  }, [profile, languages]);

  const loadActivation = useCallback(async () => {
    setActivationLoading(true);
    try {
      const saved = await getLocalSetup();
      setActivation(saved ?? null);
      setCanRegister(typeof (window as any).electronAPI?.updateHotkey === "function");
      if (saved?.dictationHotkey) {
        setCanRegister(await canRegisterHotkey(saved.dictationHotkey).catch(() => true));
      }
    } catch {
      setActivation(null);
    } finally {
      setActivationLoading(false);
    }
  }, []);

  const persistActivation = useCallback(async (choice: ActivationChoice): Promise<boolean> => {
    setSavingActivation(true);
    try {
      if (choice.dictationHotkey && typeof (window as any).electronAPI?.updateHotkey === "function") {
        await (window as any).electronAPI.updateHotkey(choice.dictationHotkey).catch(() => null);
      }
      // The Skip path bypasses the step's own registration, so persist the
      // assistant chord to the voiceAgent slot here too (no-op if the step
      // already registered the same value; the setter rolls back on failure).
      if (choice.assistantEnabled && choice.assistantHotkey) {
        await useSettingsStore.getState().setVoiceAgentKey(choice.assistantHotkey).catch(() => null);
      }
      await saveLocalSetup({
        version: 1,
        activationMode: choice.dictationMode,
        dictationHotkey: choice.dictationHotkey,
        assistantHotkey: choice.assistantEnabled ? choice.assistantHotkey : "",
        completed: false,
      });
      const saved = await getLocalSetup();
      setActivation(saved ?? null);
      return true;
    } catch (e) {
      setError(normalizeError(e));
      return false;
    } finally {
      setSavingActivation(false);
    }
  }, []);

  const completeActivation = useCallback(async (choice: ActivationChoice) => {
    const ok = await persistActivation(choice);
    if (!ok) return false;
    try {
      await saveLocalSetup({
        version: 1,
        activationMode: choice.dictationMode,
        dictationHotkey: choice.dictationHotkey,
        assistantHotkey: choice.assistantEnabled ? choice.assistantHotkey : "",
        completed: true,
      });
    } catch (e) {
      setError(normalizeError(e));
      return false;
    }
    onComplete();
    return true;
  }, [persistActivation, onComplete]);

  useEffect(() => {
    if (doneRef.current) return;
    doneRef.current = true;
    detect();
    loadActivation();
  }, [detect, loadActivation]);

  const goNext = useCallback((next: SetupStepId) => setStep(next), []);

  if (loading && step === "detection" && !profile) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        <p className="text-sm text-muted-foreground">{t("localOnboarding.shell.loading")}</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="px-6 py-4">
        <StepHeader
          title={t("localOnboarding.shell.title")}
          description={t("localOnboarding.shell.description")}
        />
      </div>
      <StepProgress steps={ALL_STEPS} current={step} />
      <div className="flex flex-1 min-h-0 flex-col p-6">
        {step === "detection" && (
          <DetectionStep
            result={profile}
            loading={loading}
            error={error}
            onRetry={detect}
            onContinue={() => goNext("languages")}
          />
        )}

        {step === "languages" && (
          <LanguageStep
            languages={languages}
            allLanguages={defaultLanguages}
            onChange={setLanguages}
            onContinue={() => {
              if (languages.length > 0) {
                buildStack();
                goNext("stack");
              }
            }}
          />
        )}

        {step === "stack" && (
          <RecommendedStackStep
            recommendation={recommendation}
            loading={stackLoading}
            error={stackError}
            onSelect={(choice) => {
              setStackChoice(choice);
              setRecommendation((prev) => (prev ? { ...prev, choice } : prev));
            }}
            onChangeTranscription={(modelId) => {
              setStackChoice((prev) => (prev ? { ...prev, transcriptionModelId: modelId } : prev));
              setRecommendation((prev) =>
                prev ? { ...prev, choice: { ...prev.choice, transcriptionModelId: modelId } } : prev,
              );
            }}
            onChangeAssistant={(modelId) => {
              setStackChoice((prev) => (prev ? { ...prev, assistantModelId: modelId } : prev));
              setRecommendation((prev) =>
                prev ? { ...prev, choice: { ...prev.choice, assistantModelId: modelId } } : prev,
              );
            }}
            onContinue={() => {
              const effective = stackChoice ?? recommendation?.choice ?? null;
              if (effective) {
                const items: LocalSetupItem[] = [];
                const tModel = recommendation?.transcriptionOptions.find(
                  (m) => m.modelId === effective.transcriptionModelId,
                );
                if (effective.transcriptionModelId && !tModel?.downloaded) {
                  items.push({
                    modelId: effective.transcriptionModelId,
                    label: tModel?.displayName ?? effective.transcriptionModelId,
                    description: tModel?.description ?? "",
                    sizeLabel: tModel?.sizeLabel ?? "",
                  });
                }
                const aModel = recommendation?.assistantOptions.find(
                  (m) => m.modelId === effective.assistantModelId,
                );
                if (effective.assistantModelId && !aModel?.downloaded) {
                  items.push({
                    modelId: effective.assistantModelId,
                    label: aModel?.displayName ?? effective.assistantModelId,
                    description: aModel?.description ?? "",
                    sizeLabel: aModel?.sizeLabel ?? "",
                  });
                }
                setDownloadSummary(items);
                setDownloadProgress(null);
                setDownloadError(null);
                goNext(items.length === 0 ? "micTest" : "download");
              } else if (downloadSummary.length === 0) {
                goNext("micTest");
              } else {
                goNext("download");
              }
            }}
          />
        )}

        {step === "download" && (
          <DownloadStep
            summary={downloadSummary}
            loading={stackLoading}
            error={downloadError}
            onProgress={(p) => {
              setDownloadProgress(p);
              if (p.failed.length > 0) {
                setDownloadError(p.failed[0]?.error ?? "download_failed");
              }
            }}
            onContinue={() => {
              if (isComplete(downloadProgress, downloadSummary) || downloadSummary.length === 0) {
                goNext("micTest");
              }
            }}
          />
        )}

        {step === "micTest" && (
          <MicTestPanel onContinue={() => goNext("activation")} />
        )}

        {step === "activation" && (
          <ActivationStep
            initial={{
              dictationMode: activation?.activationMode ?? "tap",
              dictationHotkey: activation?.dictationHotkey ?? "",
              assistantHotkey: activation?.assistantHotkey ?? "",
              assistantEnabled: Boolean(activation?.assistantHotkey ?? true),
            }}
            saved={activation}
            loading={activationLoading}
            saving={savingActivation}
            error={error}
            canRegister={canRegister}
            onContinue={(choice) => { completeActivation(choice); }}
          />
        )}
      </div>
    </div>
  );
}

// Mounts the mic capture hook only while the mic-test step is visible.
// useMicCapture owns an AudioManager (device lookup, interrupted-recovery,
// lifecycle reports), so mounting it with the shell would run all of that on
// the detection step — before the user ever reaches the microphone test.
function MicTestPanel({ onContinue }: { onContinue: () => void }) {
  const mic = useMicCapture();
  return (
    <MicTestStep
      loading={false}
      recording={mic.recording}
      transcribing={mic.transcribing}
      result={mic.result?.text ?? null}
      error={mic.error ?? null}
      onStartRecord={() => { mic.start(); }}
      onStopRecord={() => { mic.stop(); }}
      onRetry={() => { mic.cancel(); }}
      onContinue={onContinue}
    />
  );
}
