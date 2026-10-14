import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { StepHeader } from "./StepHeader";
import type { LocalStackRecommendation } from "../../lib/recommendedLocalStack";
import { Card, CardDescription, CardTitle } from "../../ui/Card";
import { Button } from "../../ui/Button";
import { Separator } from "../../ui/Separator";
import { Badge } from "../../ui/Badge";
import { StackChoice } from "./StackChoice";
import { saveLocalSetup, type LocalSetupState } from "../../api/localShellApi";

interface Props {
  recommendation: LocalStackRecommendation | null;
  loading: boolean;
  saving: boolean;
  error: string | null;
  onSelect: (choice: LocalStackRecommendation["choice"]) => void;
  onChangeTranscription: (modelId: string) => void;
  onChangeAssistant: (modelId: string) => void;
  onContinue: () => void;
}

export default function RecommendedStackStep({
  recommendation,
  loading,
  saving,
  error,
  onSelect,
  onChangeTranscription,
  onChangeAssistant,
  onContinue,
}: Props) {
  const { t } = useTranslation();
  const choice = recommendation?.choice;

  const persistChoice = useCallback(
    async (newChoice: LocalStackRecommendation["choice"]) => {
      await saveLocalSetup({
        version: 1,
        activationMode: "tap",
        dictationHotkey: "",
        assistantHotkey: "",
        completed: false,
      });
    },
    [],
  );

  if (loading) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Spinner />
        <p className="text-sm text-muted-foreground">{t("localOnboarding.stack.loading")}</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
        <p className="text-sm text-destructive">{error}</p>
        <Button onClick={onContinue} variant="outline">
          {t("localOnboarding.stack.continueWithoutRecommendation")}
        </Button>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col p-6">
      <StepHeader
        title={t("localOnboarding.stack.title")}
        description={t("localOnboarding.stack.description")}
      />

      <div className="mt-4 flex flex-col gap-4">
        <StackChoice
          label={t("localOnboarding.stack.transcription")}
          recommended={choice?.transcriptionModelId}
          current={choice?.transcriptionModelId ?? null}
          available={recommendation?.transcriptionOptions ?? []}
          onSelect={(modelId) => {
            onSelect((prev) => ({ ...prev, transcriptionModelId: modelId }));
            onChangeTranscription(modelId);
            persistChoice({ transcriptionModelId: modelId, assistantModelId: choice?.assistantModelId ?? "" });
          }}
          renderMeta={(modelId) => {
            const model = recommendation?.transcriptionOptions.find((m) => m.modelId === modelId);return model ?? null;
          }}
          renderChip={(model) => {
            const runtime = model.runtime === "online" ? t("localOnboarding.stack.chipStreaming") : t("localOnboarding.stack.chipOffline");
            return (
              <Badge variant="secondary" className="text-xs">
                {runtime}
              </Badge>
            );
          }}
        />

        <Separator />

        <StackChoice
          label={t("localOnboarding.stack.assistant")}
          recommended={choice?.assistantModelId}
          current={choice?.assistantModelId ?? null}
          available={recommendation?.assistantOptions ?? []}
          onSelect={(modelId) => {
            onSelect((prev) => ({ ...prev, assistantModelId: modelId }));
            onChangeAssistant(modelId);
            persistChoice({ transcriptionModelId: choice?.transcriptionModelId ?? "", assistantModelId: modelId });
          }}
          renderMeta={(modelId) => {
            const model = recommendation?.assistantOptions.find((m) => m.modelId === modelId);return model ?? null;
          }}
          renderChip={(model) => {
            const quant = model.quantization ?? "";
            return <Badge variant="secondary" className="text-xs">{quant ? quant.toUpperCase() : ""}</Badge>;
          }}
        />

        {recommendation?.translationPairs && recommendation.translationPairs.length > 0 && (
          <>
            <Separator />

            <div className="flex flex-col gap-2 rounded-lg border border-default bg-default p-4">
              <div className="flex items-center justify-between">
                <div className="flex flex-col">
                  <CardTitle className="text-base">{t("localOnboarding.stack.translationPairs")}</CardTitle>
                  <CardDescription className="mt-0 text-xs">{t("localOnboarding.stack.translationPairsDescription")}</CardDescription>
                </div>
                <Button variant="ghost" size="sm" type="button" onClick={onContinue} className="ml-auto">
                  {t("localOnboarding.stack.continue")}
                </Button>
              </div>
              <ul className="mt-2 flex flex-wrap gap-2">
                {recommendation.translationPairs.map((pair) => (
                  <Badge key={pair.from + "-" + pair.to} variant="outline" className="text-xs">
                    {pair.from} → {pair.to}
                  </Badge>
                ))}
              </ul>
            </div>
          </>
        )}

        {saving && (
          <p className="mt-2 text-center text-sm text-muted-foreground">{t("localOnboarding.stack.saving")}</p>
        )}
      </div>
    </div>
  );
}
