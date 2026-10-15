import { useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Loader2 } from "../icons";
import { StepHeader } from "./StepHeader";
import { Button } from "../ui/button";
import { Card, CardContent, CardDescription, CardTitle } from "../ui/card";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { useDownloadCoordinator } from "../../hooks/useDownloadCoordinator";
import type { LocalSetupItem, LocalSetupProgress, DownloadPhase } from "../../lib/localSetupState";
import { isComplete } from "../../lib/localSetupState";

interface Props {
  summary: LocalSetupItem[];
  loading: boolean;
  error: string | null;
  onProgress?: (progress: LocalSetupProgress) => void;
  onContinue: () => void;
}

export default function DownloadStep({ summary, loading, error, onProgress, onContinue }: Props) {
  const { t } = useTranslation();
  const { progress, start: startDownload, cancel: cancelDownload } = useDownloadCoordinator({
    items: summary,
    onProgress,
  });

  useEffect(() => {
    if (summary.length > 0 && !progress) {
      startDownload();
    }
  }, [summary, progress, startDownload]);

  const itemProgress = useCallback(
    (modelId: string) => {
      if (!progress) return null;
      const active = progress.active.find((a) => a.modelId === modelId);
      if (active) return active;
      const done = progress.done.find((d) => d.modelId === modelId);
      if (done) return { ...done, phase: "complete" as DownloadPhase, percentage: 100 };
      const failed = progress.failed.find((f) => f.modelId === modelId);
      if (failed) return { ...failed, phase: "failed" as DownloadPhase, percentage: 0 };
      return null;
    },
    [progress],
  );

  const overall = useCallback(() => {
    if (!progress) return null;
    if (progress.active.length > 0) {
      const avgP = Math.round(progress.active.reduce((s, a) => s + (a.percentage || 0), 0) / progress.active.length);
      return { percent: avgP, phase: progress.active.some((a) => a.phase === "installing") ? "installing" : "progress" };
    }
    if (progress.failed.length > 0) return { percent: 0, phase: "failed" as DownloadPhase };
    if (progress.done.length >= summary.length && summary.length > 0) return { percent: 100, phase: "complete" as DownloadPhase };
    return null;
  }, [progress, summary]);

  const overallState = overall();
  const complete = isComplete(progress, summary);

  if (loading) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        <p className="text-sm text-muted-foreground">{t("localOnboarding.download.loading")}</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col p-6">
      <StepHeader
        title={t("localOnboarding.download.title")}
        description={t("localOnboarding.download.description")}
      />

      {error && (
        <Alert className="mt-4" variant="destructive">
          <AlertTitle>{t("localOnboarding.download.errorTitle")}</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <div className="mt-4 flex flex-col gap-3">
        {summary.map((item) => {
          const prog = itemProgress(item.modelId);
          const failed = progress?.failed.find((f) => f.modelId === item.modelId) ?? null;
          const done = progress?.done.some((d) => d.modelId === item.modelId);

          return (
            <Card key={item.modelId} className="bg-default">
              <CardContent className="flex flex-col gap-3 p-4">
                <div className="flex items-center justify-between">
                  <div className="flex flex-col">
                    <CardTitle className="text-base">{item.label}</CardTitle>
                    <CardDescription className="mt-0 text-xs">
                      {item.description ?? ""}
                      {item.sizeLabel ? ` · ${item.sizeLabel}` : ""}
                    </CardDescription>
                  </div>
                  <div className="flex items-center gap-2">
                    {done ? (
                      <span className="text-sm text-green-600">{t("localOnboarding.download.installed")}</span>
                    ) : prog ? (
                      <span className="text-sm text-muted-foreground">
                        {t(`localOnboarding.download.phase.${prog.phase}`, { percent: prog.percentage })}
                      </span>
                    ) : null}
                  </div>
                </div>

                {prog && prog.phase !== "complete" && (
                  <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className={`h-full rounded-full ${
                        prog.phase === "failed" ? "bg-destructive" : "bg-primary"
                      }`}
                      style={{ width: `${prog.percentage}%` }}
                    />
                  </div>
                )}

                <div className="flex items-center gap-2">
                  {done ? null : prog ? (
                    <Button
                      variant="outline"
                      size="sm"
                      type="button"
                      onClick={cancelDownload}
                    >
                      {prog.phase === "failed" ? t("localOnboarding.download.retry") : t("localOnboarding.download.cancel")}
                    </Button>
                  ) : (
                    <Button size="sm" type="button" onClick={startDownload} className="ml-auto">
                      {t("localOnboarding.download.download")}
                    </Button>
                  )}
                </div>

                {failed && (
                  <Alert className="border-border bg-muted/50" variant="destructive">
                    <AlertTitle className="text-xs">{t("localOnboarding.download.failedItem")}</AlertTitle>
                    <AlertDescription className="text-xs">
                      {failed.error ?? t("localOnboarding.download.failedGeneric")}
                    </AlertDescription>
                    <Button
                      variant="ghost"
                      size="sm"
                      type="button"
                      className="mt-2 w-full"
                      onClick={startDownload}
                    >
                      {t("localOnboarding.download.retry")}
                    </Button>
                  </Alert>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>

      {overallState && overallState.phase !== "complete" && (
        <div className="mt-6 flex items-center gap-3 rounded-lg border border-default bg-default p-4">
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            {overallState.phase === "failed"
              ? t("localOnboarding.download.someFailed")
              : t("localOnboarding.download.overallStatus", {
                  phase: t(`localOnboarding.download.phase.${overallState.phase}`),
                  percent: overallState.percent,
                })}
          </p>
        </div>
      )}

      {(complete || overallState?.phase === "complete") && (
        <div className="mt-6 flex justify-center">
          <Button size="lg" type="button" onClick={onContinue}>
            {t("localOnboarding.download.allInstalled")}
          </Button>
        </div>
      )}
    </div>
  );
}
