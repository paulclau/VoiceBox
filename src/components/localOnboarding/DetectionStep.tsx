import { Loader2 } from "../icons";
import { useTranslation } from "react-i18next";
import { StepHeader } from "./StepHeader";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import type { HardwareProfile } from "../../lib/hardwareProfile";

interface Props {
  result: HardwareProfile | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onContinue: () => void;
}

export default function DetectionStep({ result, loading, error, onRetry, onContinue }: Props) {
  const { t } = useTranslation();

  if (loading) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        <p className="text-sm text-muted-foreground">{t("localOnboarding.detection.loading")}</p>
      </div>
    );
  }

  if (error && !result) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
        <p className="text-sm text-destructive">{error}</p>
        <Button onClick={onRetry} variant="outline">
          {t("localOnboarding.detection.retry")}
        </Button>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col p-6">
      <StepHeader
        title={t("localOnboarding.detection.title")}
        description={t("localOnboarding.detection.description")}
      />
      <div className="mt-4 flex flex-col gap-4">
        <Card className="bg-default">
          <CardContent className="flex flex-col gap-2 p-4 text-sm">
            <p className="font-medium text-default-foreground">{t("localOnboarding.detection.resultLabel")}</p>
            <ul className="grid grid-cols-2 gap-2 list-disc pl-5 text-muted-foreground">
              <li>{t("localOnboarding.detection.platform", { platform: result?.platform ?? "—" })}</li>
              <li>{t("localOnboarding.detection.class", { class: result?.class ?? "—" })}</li>
              <li>{t("localOnboarding.detection.ramHint", { hint: result?.signals.ramHint ?? "—" })}</li>
              <li>{t("localOnboarding.detection.diskHint", { hint: result?.signals.diskHint ?? "—" })}</li>
              <li>{t("localOnboarding.detection.hasGpuRuntime", { yes: result?.signals.hasGpuRuntime ? "true" : "false" })}</li>
              <li>{t("localOnboarding.detection.hasStreamingRuntime", { yes: result?.signals.hasStreamingRuntime ? "true" : "false" })}</li>
            </ul>
          </CardContent>
        </Card>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button onClick={onContinue} variant="outline" className="ml-auto">
          {t("localOnboarding.detection.continue")}
        </Button>
      </div>
    </div>
  );
}
