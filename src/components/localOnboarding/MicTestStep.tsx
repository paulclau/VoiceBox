import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { StepHeader } from "./StepHeader";
import { Button } from "../../ui/Button";
import { Card, CardContent, CardTitle } from "../../ui/Card";
import { Spinner } from "../../ui/Spinner";
import { useLocalStorage } from "../../hooks/useLocalStorage";
import { transcribeLocalParakeet, transcribeLocalWhisper } from "../../api/sttApi";

interface Props {
  loading: boolean;
  recording: boolean;
  transcribing: boolean;
  result: string | null;
  error: string | null;
  onStartRecord: () => void;
  onStopRecord: () => void;
  onRetry: () => void;
  onContinue: () => void;
}

export default function MicTestStep({
  loading,
  recording,
  transcribing,
  result,
  error,
  onStartRecord,
  onStopRecord,
  onRetry,
  onContinue,
}: Props) {
  const { t } = useTranslation();
  const [localTranscript, setLocalTranscript] = useLocalStorage("local-mic-test-transcript", "");

  const handleRetry = useCallback(() => {
    setLocalTranscript("");
    onRetry();
  }, [onRetry]);

  const handleContinue = useCallback(() => {
    if (result || localTranscript) {
      onContinue();
    }
  }, [result, localTranscript, onContinue]);

  if (loading) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <Spinner />
        <p className="text-sm text-muted-foreground">{t("localOnboarding.micTest.loading")}</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col p-6">
      <StepHeader
        title={t("localOnboarding.micTest.title")}
        description={t("localOnboarding.micTest.description")}
      />

      <div className="mt-4 flex flex-col gap-4">
        <Card className="bg-default">
          <CardContent className="flex flex-col gap-4 p-4">
            <CardTitle className="text-base">{t("localOnboarding.micTest.testLabel")}</CardTitle>

            <div className="flex items-center justify-between">
              <div className="flex flex-col">
                <span className="text-sm font-medium">
                  {recording ? t("localOnboarding.micTest.recording") : t("localOnboarding.micTest.idle")}
                </span>
                {transcribing && (
                  <span className="mt-1 text-sm text-muted-foreground">
                    {t("localOnboarding.micTest.transcribing")}
                  </span>
                )}
              </div>
              <Button
                type="button"
                variant={recording ? "destructive" : "default"}
                onClick={recording ? onStopRecord : onStartRecord}
                disabled={transcribing}
              >
                {recording ? t("localOnboarding.micTest.stop") : t("localOnboarding.micTest.start")}
              </Button>
            </div>

            <div className="h-3 w-full overflow-hidden rounded-full bg-muted">
              <div className="h-full animate-pulse rounded-full bg-accent" style={{ width: recording ? "60%" : "0%" }} />
            </div>
          </CardContent>
        </Card>

        <Card className="bg-default">
          <CardContent className="flex flex-col gap-3 p-4">
            <CardTitle className="text-base">{t("localOnboarding.micTest.result")}</CardTitle>

            <div className="min-h-[80px] flex flex-col gap-2 rounded-lg bg-muted/50 p-3">
              {error ? (
                <>
                  <span className="text-sm text-destructive">{error}</span>
                  <div className="ml-auto flex gap-2">
                    <Button variant="outline" size="sm" onClick={handleRetry}>
                      {t("localOnboarding.micTest.retry")}
                    </Button>
                  </div>
                </>
              ) : result ? (
                <>
                  <span className="text-sm text-default-foreground whitespace-pre-wrap">{result}</span>
                  <div className="ml-auto flex gap-2">
                    <Button variant="ghost" size="sm" onClick={handleContinue}>
                      {t("localOnboarding.micTest.continue")}
                    </Button>
                  </div>
                </>
              ) : localTranscript ? (
                <>
                  <span className="text-sm text-muted-foreground">{t("localOnboarding.micTest.noResult")}</span>
                  <span className="text-sm text-default-foreground whitespace-pre-wrap">{localTranscript}</span>
                  <div className="ml-auto flex gap-2">
                    <Button variant="ghost" size="sm" onClick={handleContinue}>
                      {t("localOnboarding.micTest.continue")}
                    </Button>
                  </div>
                </>
              ) : (
                <span className="text-sm text-muted-foreground">{t("localOnboarding.micTest.pending")}</span>
              )}
            </div>

            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={handleRetry} disabled={!error}>
                {t("localOnboarding.micTest.retry")}
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2 mt-2">
          <Button variant="outline" onClick={onContinue}>
            {t("localOnboarding.micTest.skip")}
          </Button>
          <Button onClick={handleContinue} disabled={!result && !localTranscript}>
            {t("localOnboarding.micTest.continue")}
          </Button>
        </div>
      </div>
    </div>
  );
}
