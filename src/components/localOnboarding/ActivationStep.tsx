import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { StepHeader } from "./StepHeader";
import { Button } from "../../ui/Button";
import { Card, CardContent, CardDescription, CardTitle } from "../../ui/Card";
import { Separator } from "../../ui/Separator";
import { useHotkeyRegistration } from "../../hooks/useHotkeyRegistration";
import { formatHotkeyLabel } from "../../utils/hotkeys";
import { validateHotkeyForSlot } from "../../utils/hotkeyValidation";
import { getPlatform, type Platform } from "../../utils/platform";
import { getEffectiveDefaultHotkey, getHotkeyModeInfo, type LocalSetupState } from "../../api/localShellApi";

type ActivationChoice = {
  dictationMode: "tap" | "push";
  dictationHotkey: string;
  assistantHotkey: string;
  assistantEnabled: boolean;
};

interface Props {
  initial: Partial<ActivationChoice>;
  saved?: LocalSetupState | null;
  loading: boolean;
  saving: boolean;
  error: string | null;
  canRegister: boolean;
  onSave: (choice: ActivationChoice) => Promise<boolean>;
  onContinue: () => void;
}

const ASSISTANT_DEFAULTS: Record<Platform, string> = {
  darwin: "Option+Command+Space",
  win32: "Control+Alt+Space",
  linux: "Control+Alt+Space",
};

const DICTATION_PRESETS: Record<Platform, string[]> = {
  darwin: ["GLOBE", "Option+Command+Space", "Control+Command+Space", "F8"],
  win32: ["Control+Super", "Control+Shift+Space", "F8", "F9"],
  linux: ["Control+Super", "Control+Shift+Space", "F8", "F9"],
};

export default function ActivationStep({
  initial,
  saved,
  loading,
  saving,
  error,
  canRegister,
  onSave,
  onContinue,
}: Props) {
  const { t } = useTranslation();
  const platform = getPlatform();

  const [dictationMode, setDictationMode] = useState<"tap" | "push">(initial.dictationMode ?? "tap");
  const [dictationHotkey, setDictationHotkey] = useState(initial.dictationHotkey ?? "");
  const [assistantHotkey, setAssistantHotkey] = useState(initial.assistantHotkey ?? ASSISTANT_DEFAULTS[platform]);
  const [assistantEnabled, setAssistantEnabled] = useState(initial.assistantEnabled ?? true);

  const dictationErrorRef = useRef<string | null>(null);
  const assistantErrorRef = useRef<string | null>(null);

  const dictationRegister = useHotkeyRegistration({
    onSuccess: (hotkey) => {
      setDictationHotkey(hotkey);
      dictationErrorRef.current = null;
    },
    onError: (message) => {
      dictationErrorRef.current = message;
    },
    showSuccessToast: false,
    showErrorToast: false,
    registerFn: canRegister ? window.electronAPI?.updateHotkey : undefined,
  });

  const [defaultHotkey, setDefaultHotkey] = useState<string | null>(null);
  const [pttInfo, setPttInfo] = useState<{ supportsPushToTalk?: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    getEffectiveDefaultHotkey()
      .then((key) => {
        if (!cancelled) setDefaultHotkey(key ?? "");
      })
      .catch(() => {});
    if (dictationHotkey) {
      getHotkeyModeInfo(dictationHotkey).then((info) => {
        if (!cancelled) setPttInfo(info ?? null);
      });
    }
    return () => {
      cancelled = true;
    };
  }, [dictationHotkey]);

  const validateDictation = useCallback(
    (hotkey: string) => {
      const conflict = validateHotkeyForSlot(
        hotkey,
        { "localOnboarding.activation.dictationLabel": saved?.dictationHotkey ?? "" },
        t,
      );
      if (conflict) return conflict;
      if (!hotkey) return t("localOnboarding.activation.dictationRequired");
      return null;
    },
    [saved?.dictationHotkey, t],
  );

  const validateAssistant = useCallback(
    (hotkey: string) => {
      if (!assistantEnabled) return null;
      const conflict = validateHotkeyForSlot(
        hotkey,
        {
          "localOnboarding.activation.dictationLabel": dictationHotkey,
          "localOnboarding.activation.assistantLabel": saved?.assistantHotkey ?? "",
        },
        t,
      );
      if (conflict) return conflict;
      if (!hotkey) return t("localOnboarding.activation.assistantRequired");
      return null;
    },
    [assistantEnabled, dictationHotkey, saved?.assistantHotkey, t],
  );

  const choosePreset = useCallback(
    (hotkey: string) => {
      setDictationHotkey(hotkey);
      dictationErrorRef.current = null;
    },
    [],
  );

  const saveDictationHotkey = useCallback(
    async (hotkey: string) => {
      dictationErrorRef.current = null;
      if (dictationMode === "push" && pttInfo?.supportsPushToTalk !== true) {
        setDictationMode("tap");
      }
      const registered = await dictationRegister.registerHotkey(hotkey);
      return registered && !dictationErrorRef.current;
    },
    [dictationMode, pttInfo, dictationRegister],
  );

  const saveAssistantHotkey = useCallback(
    async (hotkey: string) => {
      assistantErrorRef.current = null;
      if (!window.electronAPI?.updateHotkey) {
        setAssistantHotkey(hotkey);
        return true;
      }
      const registered = await window.electronAPI.updateHotkey(hotkey);
      const ok = Boolean(registered?.success !== false);
      if (!ok) {
        assistantErrorRef.current = registered?.message ?? t("localOnboarding.activation.registerFailed");
      } else {
        setAssistantHotkey(hotkey);
      }
      return ok;
    },
    [t],
  );

  const handleSave = useCallback(
    async (choice: ActivationChoice) => {
      if (!choice.dictationHotkey) {
        dictationErrorRef.current = t("localOnboarding.activation.dictationRequired");
        return false;
      }
      if (assistantEnabled && !choice.assistantHotkey) {
        assistantErrorRef.current = t("localOnboarding.activation.assistantRequired");
        return false;
      }

      const dictationOk = await saveDictationHotkey(choice.dictationHotkey);
      if (!dictationOk) return false;

      if (assistantEnabled) {
        const assistantOk = await saveAssistantHotkey(choice.assistantHotkey);
        if (!assistantOk) return false;
      } else {
        setAssistantHotkey("");
      }

      return true;
    },
    [saveDictationHotkey, saveAssistantHotkey, assistantEnabled, t],
  );

  const canProceed =
    dictationHotkey &&
    !dictationErrorRef.current &&
    (!assistantEnabled || (assistantHotkey && !assistantErrorRef.current));

  return (
    <div className="flex h-full flex-col p-6">
      <StepHeader
        title={t("localOnboarding.activation.title")}
        description={t("localOnboarding.activation.description")}
      />

      <div className="mt-4 flex flex-col gap-4">
        <Card className="bg-default">
          <CardContent className="flex flex-col gap-4 p-4">
            <div className="flex items-center justify-between">
              <CardTitle className="text-base">{t("localOnboarding.activation.dictation")}</CardTitle>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant={dictationMode === "tap" ? "default" : "outline"}
                  size="sm"
                  onClick={() => setDictationMode("tap")}
                >
                  {t("localOnboarding.activation.tap")}
                </Button>
                <Button
                  type="button"
                  variant={dictationMode === "push" ? "default" : "outline"}
                  size="sm"
                  disabled={!canRegister || pttInfo?.supportsPushToTalk !== true}
                  onClick={() => setDictationMode("push")}
                >
                  {t("localOnboarding.activation.hold")}
                </Button>
              </div>
            </div>

            {dictationMode === "push" && pttInfo?.supportsPushToTalk !== true && (
              <CardDescription className="text-xs text-muted-foreground">
                {t("localOnboarding.activation.pushUnavailable")}
              </CardDescription>
            )}

            <div className="flex flex-wrap gap-2">
              {DICTATION_PRESETS[platform].map((preset) => {
                const selected = dictationHotkey === preset;
                return (
                  <Button
                    key={preset}
                    type="button"
                    variant={selected ? "default" : "outline"}
                    size="sm"
                    onClick={() => choosePreset(preset)}
                  >
                    {formatHotkeyLabel(preset)}
                  </Button>
                );
              })}
            </div>

            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                className="flex-1"
                onClick={() => {
                  const candidate = prompt(t("localOnboarding.activation.customPrompt"), dictationHotkey);
                  if (candidate && candidate.trim()) {
                    const normalized = candidate.trim();
                    const conflict = validateDictation(normalized);
                    if (!conflict) {
                      saveDictationHotkey(normalized).then((ok) => {
                        if (!ok) {
                          dictationErrorRef.current = t("localOnboarding.activation.invalidHotkey");
                        }
                      });
                    } else {
                      dictationErrorRef.current = conflict;
                    }
                  }
                }}
              >
                {t("localOnboarding.activation.custom")}
              </Button>
              <div className="flex flex-1 flex-col gap-1">
                {dictationErrorRef.current ? (
                  <div className="text-sm text-destructive">{dictationErrorRef.current}</div>
                ) : dictationHotkey ? (
                  <div className="text-sm text-muted-foreground">{formatHotkeyLabel(dictationHotkey)}</div>
                ) : (
                  <div className="text-sm text-muted-foreground">{t("localOnboarding.activation.selectHotkey")}</div>
                )}
              </div>
            </div>
          </CardContent>
        </Card>

        {assistantEnabled && (dictationMode === "push" || saved?.assistantHotkey !== assistantHotkey) && (
          <>
            <Separator />
            <Card className="bg-default">
              <CardContent className="flex flex-col gap-4 p-4">
                <CardTitle className="text-base">{t("localOnboarding.activation.assistant")}</CardTitle>

                <div className="flex flex-wrap gap-2">
                  {["Control+Alt+Space", "Control+Shift+Space", "F9", "F10"].map((preset) => {
                    const selected = assistantHotkey === preset;
                    return (
                      <Button
                        key={preset}
                        type="button"
                        variant={selected ? "default" : "outline"}
                        size="sm"
                        onClick={() => {
                          setAssistantHotkey(preset);
                          assistantErrorRef.current = null;
                        }}
                      >
                        {formatHotkeyLabel(preset)}
                      </Button>
                    );
                  })}
                </div>

                <div className="flex gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    className="flex-1"
                    onClick={() => {
                      const candidate = prompt(t("localOnboarding.activation.customPrompt"), assistantHotkey);
                      if (candidate && candidate.trim()) {
                        const conflict = validateAssistant(candidate.trim());
                        if (!conflict) {
                          saveAssistantHotkey(candidate.trim());
                        } else {
                          assistantErrorRef.current = conflict;
                        }
                      }
                    }}
                  >
                    {t("localOnboarding.activation.custom")}
                  </Button>
                  <div className="flex flex-1 flex-col gap-1">
                    {assistantErrorRef.current ? (
                      <div className="text-sm text-destructive">{assistantErrorRef.current}</div>
                    ) : assistantHotkey ? (
                      <div className="text-sm text-muted-foreground">{formatHotkeyLabel(assistantHotkey)}</div>
                    ) : (
                      <div className="text-sm text-muted-foreground">{t("localOnboarding.activation.selectAssistantHotkey")}</div>
                    )}
                  </div>
                </div>
              </CardContent>
            </Card>
          </>
        )}

        {error && <div className="text-sm text-destructive">{error}</div>}
        {saving && <div className="text-sm text-muted-foreground">{t("localOnboarding.activation.saving")}</div>}

        <div className="flex justify-end gap-2 mt-2">
          <Button variant="outline" onClick={onContinue}>
            {t("localOnboarding.activation.skip")}
          </Button>
          <Button onClick={onContinue} disabled={!canProceed || saving}>
            {t("localOnboarding.activation.continue")}
          </Button>
        </div>
      </div>
    </div>
  );
}
