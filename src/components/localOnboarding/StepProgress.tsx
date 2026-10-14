import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { SetupStepId } from "./OnboardingShell";

interface Props {
  steps: SetupStepId[];
  current: SetupStepId;
}

const LABELS: Record<SetupStepId, string> = {
  detection: "localOnboarding.progress.detection",
  languages: "localOnboarding.progress.languages",
  stack: "localOnboarding.progress.stack",
  download: "localOnboarding.progress.download",
  micTest: "localOnboarding.progress.micTest",
  activation: "localOnboarding.progress.activation",
};

export function StepProgress({ steps, current }: Props) {
  const { t } = useTranslation();
  const [visibleSteps, setVisibleSteps] = useState<SetupStepId[]>(steps);

  useEffect(() => {
    const currentIndex = steps.indexOf(current);
    if (currentIndex >= 0) {
      setVisibleSteps(steps.slice(0, currentIndex + 1));
    }
  }, [steps, current]);

  return (
    <div className="flex items-center gap-2 px-6 py-2">
      {visibleSteps.map((stepId, index) => {
        const done = stepId !== current && steps.indexOf(current) > index;
        const active = stepId === current;
        return (
          <div key={stepId} className="flex items-center gap-2">
            <div
              className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-medium ${
                done
                  ? "border-default/50 bg-default text-default-foreground"
                  : active
                    ? "border-primary bg-primary text-primary-foreground"
                    : "border-border bg-default text-muted-foreground"
              }`}
            >
              {done ? "✓" : index + 1}
            </div>
            <span
              className={`text-sm ${
                done || active ? "text-default-foreground" : "text-muted-foreground"
              }`}
            >
              {t(LABELS[stepId])}
            </span>
            {index < visibleSteps.length - 1 && (
              <div className="flex-1 h-px bg-border" />
            )}
          </div>
        );
      })}
    </div>
  );
}
