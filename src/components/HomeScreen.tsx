import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "./ui/Button";
import { Card, CardContent, CardDescription, CardTitle } from "./ui/Card";
import { Badge } from "./ui/Badge";
import { getLocalSetup, type LocalSetupState } from "./api/localShellApi";
import { formatHotkeyLabel } from "./utils/hotkeys";

export default function HomeScreen() {
  const { t } = useTranslation();
  const [setup, setSetup] = useState<LocalSetupState | null>(null);
  const [loading, setLoading] = useState(true);

  const loadSetup = useCallback(async () => {
    try {
      const value = await getLocalSetup();
      setSetup(value);
    } catch {
      setSetup(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSetup();
  }, [loadSetup]);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-sm text-muted-foreground">{t("home.loading")}</div>
      </div>
    );
  }

  const dictationHotkey = setup?.dictationHotkey ? formatHotkeyLabel(setup.dictationHotkey) : null;
  const assistantHotkey = setup?.assistantHotkey ? formatHotkeyLabel(setup.assistantHotkey) : null;

  return (
    <div className="flex h-full flex-col gap-6 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-default-foreground">{t("home.title")}</h1>
        {setup?.completed && (
          <Badge variant="secondary" className="text-xs">
            {t("home.localReady")}
          </Badge>
        )}
      </header>

      <div className="flex flex-col gap-4">
        <Card className="bg-default">
          <CardContent className="flex flex-col gap-3 p-4">
            <div className="flex items-center justify-between">
              <CardTitle className="text-base">{t("home.dictation")}</CardTitle>
              {dictationHotkey && <Badge variant="outline" className="text-xs">{dictationHotkey}</Badge>}
            </div>
            <CardDescription className="text-sm">{t("home.dictationDescription")}</CardDescription>
            <Button onClick={() => {}} variant="outline">{t("home.open")}</Button>
          </CardContent>
        </Card>

        <Card className="bg-default">
          <CardContent className="flex flex-col gap-3 p-4">
            <div className="flex items-center justify-between">
              <CardTitle className="text-base">{t("home.assistant")}</CardTitle>
              {assistantHotkey && <Badge variant="outline" className="text-xs">{assistantHotkey}</Badge>}
            </div>
            <CardDescription className="text-sm">{t("home.assistantDescription")}</CardDescription>
            <Button onClick={() => {}} variant="outline">{t("home.open")}</Button>
          </CardContent>
        </Card>

        <Card className="bg-default">
          <CardContent className="flex flex-col gap-3 p-4">
            <CardTitle className="text-base">{t("home.translate")}</CardTitle>
            <CardDescription className="text-sm">{t("home.translateDescription")}</CardDescription>
            <Button onClick={() => {}} variant="outline">{t("home.open")}</Button>
          </CardContent>
        </Card>
      </div>

      <main className="flex flex-col gap-2">
        <h2 className="text-lg font-medium text-default-foreground">{t("home.manage")}</h2>
        <Button onClick={() => {}} variant="outline">{t("home.models")}</Button>
        <Button onClick={() => {}} variant="outline">{t("home.settings")}</Button>
      </main>
    </div>
  );
}
