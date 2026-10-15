import { useTranslation } from "react-i18next";
import { Loader2 } from "../icons";
import { StepHeader } from "./StepHeader";
import type { LocalStackRecommendation } from "../../lib/recommendedLocalStack";
import { CardDescription, CardTitle } from "../ui/card";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { StackChoice } from "./StackChoice";

type StackChoiceValue = LocalStackRecommendation["choice"];

interface Props {
  recommendation: LocalStackRecommendation | null;
  loading: boolean;
  error: string | null;
  onSelect: (choice: StackChoiceValue) => void;
  onChangeTranscription: (modelId: string) => void;
  onChangeAssistant: (modelId: string) => void;
  onContinue: () => void;
}

export default function RecommendedStackStep({
  recommendation,
  loading,
  error,
  onSelect,
  onChangeTranscription,
  onChangeAssistant,
  onContinue,
}: Props) {
  const { t } = useTranslation();
  const choice = recommendation?.choice;

  if (loading) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
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
          recommended={choice?.transcriptionModelId ?? null}
          current={choice?.transcriptionModelId ?? null}
          available={recommendation?.transcriptionOptions ?? []}
          onSelect={(modelId) => {
            const next: StackChoiceValue = {
              transcriptionModelId: modelId,
              assistantModelId: choice?.assistantModelId ?? "",
            };
            onSelect(next);
            onChangeTranscription(modelId);
          }}
          renderMeta={(modelId) => {
            const model = recommendation?.transcriptionOptions.find((m) => m.modelId === modelId);
            return model ?? null;
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

        <div className="h-px bg-border" aria-hidden="true" />

        <StackChoice
          label={t("localOnboarding.stack.assistant")}
          recommended={choice?.assistantModelId ?? null}
          current={choice?.assistantModelId ?? null}
          available={recommendation?.assistantOptions ?? []}
          onSelect={(modelId) => {
            const next: StackChoiceValue = {
              transcriptionModelId: choice?.transcriptionModelId ?? "",
              assistantModelId: modelId,
            };
            onSelect(next);
            onChangeAssistant(modelId);
          }}
          renderMeta={(modelId) => {
            const model = recommendation?.assistantOptions.find((m) => m.modelId === modelId);
            return model ?? null;
          }}
          renderChip={(model) => {
            const quant = model.quantization ?? "";
            return <Badge variant="secondary" className="text-xs">{quant ? quant.toUpperCase() : ""}</Badge>;
          }}
        />

        {recommendation?.translationPairs && recommendation.translationPairs.length > 0 && (
          <>
            <div className="h-px bg-border" aria-hidden="true" />

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

      </div>
    </div>
  );
}
