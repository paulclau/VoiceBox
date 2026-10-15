import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { StepHeader } from "./StepHeader";
import { StepProgress } from "./StepProgress";
import DetectionStep from "./DetectionStep";
import LanguageStep from "./LanguageStep";
import RecommendedStackStep from "./RecommendedStackStep";
import DownloadStep from "./DownloadStep";
import MicTestStep from "./MicTestStep";
import ActivationStep from "./ActivationStep";
import { buildHardwareProfile, type HardwareProfile } from "../../lib/hardwareProfile";
import { recommendFromProfile } from "../../lib/recommendedLocalStack";
import type { LocalStackRecommendation } from "../../lib/recommendedLocalStack";
import type { LocalSetupProgress, LocalSetupItem, DownloadPhase } from "../../lib/localSetupState";
import type { SpokenLanguage } from "../../lib/localLanguages";
import type { LocalSetupState } from "../../api/localShellApi";
import { getLocalSetup, saveLocalSetup } from "../../api/localShellApi";
import { useDownloadCoordinator } from "../../hooks/useDownloadCoordinator";
import { transcribeLocalAudio } from "../../api/sttApi";
import { Spinner } from "../../ui/Spinner";
import { Button } from "../../ui/button";
import { Card, CardContent } from "../../ui/card";
import { Separator } from "../../ui/separator";
import { Badge } from "../../ui/badge";

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

export default function OnboardingShell({ onComplete }: Props) {
  const { t } = useTranslation();

  const [step, setStep] = useState<SetupStepId>("detection");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [profile, setProfile] = useState<HardwareProfile | null>(null);
  const [languages, setLanguages] = useState<SpokenLanguage[]>([]);
  const [allLanguages, setAllLanguages] = useState<SpokenLanguage[]>([]);
  const [recommendation, setRecommendation] = useState<LocalStackRecommendation | null>(null);
  const [stackLoading, setStackLoading] = useState(false);
  const [stackSaving, setStackSaving] = useState(false);
  const [stackError, setStackError] = useState<string | null>(null);
  const [stackChoice, setStackChoice] = useState<LocalStackRecommendation["choice"] | null>(null);
  const [progress, setProgress] = useState<LocalSetupProgress | null>(null);
  const [downloadSummary, setDownloadSummary] = useState<LocalSetupItem[]>([]);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [micTestResult, setMicTestResult] = useState<string | null>(null);
  const [micTestError, setMicTestError] = useState<string | null>(null);
  const [micRecording, setMicRecording] = useState(false);
  const [micTranscribing, setMicTranscribing] = useState(false);
  const [activationLoading, setActivationLoading] = useState(true);
  const [activation, setActivation] = useState<LocalSetupState | null>(null);

  const doneRef = useRef(false);

  const allLanguagesLoaded = useCallback(async () => {
    try {
      const data = await import("../../lib/localLanguages").then((m) => (m as any).default ?? []);
      setAllLanguages(Array.isArray(data) ? data : []);
    } catch {
      setAllLanguages([]);
    }
  }, []);

  const detectAndContinue = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const p = buildHardwareProfile();
      setProfile(p);
      const stack = recommendFromProfile(p, languages, false);
      setRecommendation(stack);
      setLoading(false);
      return true;
    } catch (e) {
      setError(normalizeError(e));
      setLoading(false);
      return false;
    }
  }, [languages]);

  const goNext = useCallback((next: SetupStepId) => {
    setStep(next);
  }, []);

  const startStack = useCallback(async () => {
    setStackLoading(true);
    setStackError(null);
    try {
      if (!profile) throw new Error("no_profile");
      const stack = recommendFromProfile(profile, languages, false);
      setRecommendation(stack);
      setStackLoading(false);
      setStackChoice(stack.choice);
    } catch (e) {
      setStackError(normalizeError(e));
      setStackLoading(false);
    }
  }, [profile, languages]);

  const saveStackChoice = useCallback(
    async (choice: LocalStackRecommendation["choice"]) => {
      setStackSaving(true);
      try {
        await saveLocalSetup({
          version: 1,
          activationMode: "tap",
          dictationHotkey: "",
          assistantHotkey: "",
          completed: false,
        });
        setStackSaving(false);
        setStackChoice(choice);
        buildDownloadSummary(choice);
        return true;
      } catch (e) {
        setStackSaving(false);
        return false;
      }
    },
    [],
  );

  const buildDownloadSummary = useCallback((choice: LocalStackRecommendation["choice"]) => {
    const items: LocalSetupItem[] = [];
    if (choice.transcriptionModelId) {
      const model = recommendation?.transcriptionOptions.find((m) => m.modelId === choice.transcriptionModelId);
      items.push({
        modelId: choice.transcriptionModelId,
        label: model?.displayName ?? choice.transcriptionModelId,
        description: model?.description ?? "",
        sizeLabel: model?.sizeLabel ?? "",
      });
    }
    if (choice.assistantModelId) {
      const model = recommendation?.assistantOptions.find((m) => m.modelId === choice.assistantModelId);
      items.push({
        modelId: choice.assistantModelId,
        label: model?.displayName ?? choice.assistantModelId,
        description: model?.description ?? "",
        sizeLabel: model?.sizeLabel ?? "",
      });
    }
    setDownloadSummary(items);
  }, [recommendation]);

  const startDownload = useCallback(async () => {
    setDownloadError(null);
    setProgress({
      active: downloadSummary.map((item) => ({
        modelId: item.modelId,
        phase: "progress",
        percentage: 0,
      })),
      done: [],
      failed: [],
      startedAt: Date.now(),
    });
  }, [downloadSummary]);

  const downloadProgress = useCallback(
    (modelId: string, phase: DownloadPhase, percentage: number, errorMsg?: string) => {
      setProgress((prev) => {
        if (!prev) return prev;
        const idx = prev.active.findIndex((a) => a.modelId === modelId);
        if (idx >= 0) {
          const entry = prev.active[idx];
          const next = {
            ...entry,
            phase,
            percentage,
            error: errorMsg ?? entry.error,
          };
          if (phase === "complete") {
            const active = [...prev.active];
            active.splice(idx, 1);
            return {
              ...prev,
              active,
              done: [...prev.done, next],
            };
          }
          if (phase === "failed") {
            const active = [...prev.active];
            active.splice(idx, 1);
            return {
              ...prev,
              active,
              failed: [...prev.failed, next],
            };
          }
          const active = [...prev.active];
          active[idx] = next;
          return { ...prev, active };
        }
        return prev;
      });
    },
    [],
  );

  const loadActivation = useCallback(async () => {
    setActivationLoading(true);
    try {
      const saved = await getLocalSetup();
      setActivation(saved ?? null);
    } catch {
      setActivation(null);
    } finally {
      setActivationLoading(false);
    }
  }, []);

  const completeActivation = useCallback(
    async (choice: ActivationChoice) => {
      try {
        await saveLocalSetup({
          version: 1,
          activationMode: choice.dictationMode,
          dictationHotkey: choice.dictationHotkey,
          assistantHotkey: choice.assistantHotkey,
          completed: true,
        });
        onComplete();
      } catch (e) {
        setError(normalizeError(e));
      }
    },
    [onComplete],
  );

  const retryMicTest = useCallback(async () => {
    setMicTestError(null);
    setMicTestResult(null);
  }, []);

  useEffect(() => {
    if (doneRef.current) return;
    doneRef.current = true;
    allLanguagesLoaded();
    detectAndContinue();
    loadActivation();
  }, []);

  if (loading && step === "detection") {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Spinner />
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
        {error && (
          <div className="mb-4 rounded-lg border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">
            {error}
          </div>
        )}

        {step === "detection" && (
          <DetectionStep
            onDetect={detectAndContinue}
            result={profile ? { platform: profile.platform, class: profile.class, ram: profile.signals.ram, disk: profile.signals.disk, hasGpuRuntime: profile.signals.hasGpuRuntime, hasStreamingRuntime: profile.signals.hasStreamingRuntime } : null}
            loading={loading}
            error={error}
            onContinue={goNext}
          />
        )}

        {step === "languages" && (
          <LanguageStep
            languages={languages}
            allLanguages={allLanguages}
            onChange={setLanguages}
            onContinue={() => {
              if (languages.length > 0) {
                startStack();
                goNext("stack");
              }
            }}
          />
        )}

        {step === "stack" && (
          <RecommendedStackStep
            recommendation={recommendation}
            loading={stackLoading}
            saving={stackSaving}
            error={stackError}
            onSelect={(choice) => {
              setStackChoice(choice);
            }}
            onChangeTranscription={(modelId) => {
              setStackChoice((prev) => (prev ? { ...prev, transcriptionModelId: modelId } : null));
            }}
            onChangeAssistant={(modelId) => {
              setStackChoice((prev) => (prev ? { ...prev, assistantModelId: modelId } : null));
            }}
            onContinue={() => {
              if (stackChoice) {
                buildDownloadSummary(stackChoice);
                goNext("download");
              }
            }}
          />
        )}

        {step === "download" && (
          <DownloadStep
            summary={downloadSummary}
            progress={progress}
            loading={stackLoading}
            error={downloadError}
            onStartDownload={startDownload}
            onCancel={() => {
              setProgress((prev) => (prev ? { ...prev, active: [], failed: [] } : prev));
            }}
            onContinue={() => {
              if (isComplete(progress, downloadSummary)) {
                goNext("micTest");
              }
            }}
          />
        )}

        {step === "micTest" && (
          <MicTestStep
            loading={stackLoading}
            recording={micRecording}
            transcribing={micTranscribing}
            result={micTestResult}
            error={micTestError}
            onStartRecord={() => {
              setMicRecording(true);
              setMicTranscribing(true);
              setMicTestResult(null);
              setMicTestError(null);
              setTimeout(() => {
                setMicTranscribing(false);
                setMicRecording(false);
                setMicTestResult("Local STT placeholder result.");
              }, 1500);
            }}
            onStopRecord={() => {
              setMicRecording(false);
              setMicTranscribing(false);
            }}
            onRetry={retryMicTest}
            onContinue={goNext}
          />
        )}

        {step === "activation" && (
          <ActivationStep
            initial={{
              dictationMode: activation?.activationMode ?? "tap",
              dictationHotkey: activation?.dictationHotkey ?? "",
              assistantHotkey: activation?.assistantHotkey ?? "",
              assistantEnabled: Boolean(activation?.assistantHotkey),
            }}
            saved={activation}
            loading={activationLoading}
            saving={stackSaving}
            error={error}
            canRegister={false}
            onSave={async (choice) => {
              if (await handleSave(choice)) {
                await completeActivation(choice);
                return true;
              }
              return false;
            }}
            onContinue={() => {
              handleSave({
                dictationMode: activation?.activationMode ?? "tap",
                dictationHotkey: activation?.dictationHotkey ?? "",
                assistantHotkey: activation?.assistantHotkey ?? "",
                assistantEnabled: Boolean(activation?.assistantHotkey),
              }).then((ok) => {
                if (ok) {
                  completeActivation({
                    dictationMode: activation?.activationMode ?? "tap",
                    dictationHotkey: activation?.dictationHotkey ?? "",
                    assistantHotkey: activation?.assistantHotkey ?? "",
                    assistantEnabled: Boolean(activation?.assistantHotkey),
                  });
                }
              });
            }}
          />
        )}
      </div>
    </div>
  );
}

function handleSave(choice: ActivationChoice): Promise<boolean> {
  return Promise.resolve(true);
}

function normalizeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    const parsed = JSON.parse(JSON.stringify(error));
    if (parsed?.message) return String(parsed.message);
    if (parsed?.error) return String(parsed.error);
  } catch {}
  return "setup_error";
}

function isComplete(progress: LocalSetupProgress | null, items: LocalSetupItem[]): boolean {
  if (!progress) return false;
  if (progress.active.length > 0) return false;
  if (progress.failed.length > 0) return false;
  return progress.done.length >= items.length;
}
