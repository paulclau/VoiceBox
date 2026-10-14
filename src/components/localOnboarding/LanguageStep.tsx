import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { SearchableList } from "../../ui/SearchableList";
import { StepHeader } from "./StepHeader";
import type { SpokenLanguage } from "../../lib/localLanguages";

interface Props {
  languages: SpokenLanguage[];
  allLanguages: SpokenLanguage[];
  onChange: (languages: SpokenLanguage[]) => void;
  onContinue: () => void;
}

export default function LanguageStep({ languages, allLanguages, onChange, onContinue }: Props) {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");

  const filtered = useCallback(() => {
    const q = search.trim().toLowerCase();
    if (!q) return allLanguages;
    return allLanguages.filter((l) =>
      l.name.toLowerCase().includes(q) || l.code.toLowerCase().includes(q)
    );
  }, [allLanguages, search]);

  const toggle = useCallback(
    (language: SpokenLanguage) => {
      const next = languages.find((l) => l.code === language.code)
        ? languages.filter((l) => l.code !== language.code)
        : [...languages, language];
      onChange(next);
    },
    [languages, onChange]
  );

  return (
    <div className="flex h-full flex-col p-6">
      <StepHeader
        title={t("localOnboarding.languages.title")}
        description={t("localOnboarding.languages.description")}
      />

      <div className="mt-4 flex flex-col gap-4 flex-1 min-h-0">
        <SearchableList
          items={filtered()}
          search={search}
          onSearchChange={setSearch}
          emptyLabel={t("localOnboarding.languages.noResults")}
          renderItem={(language) => {
            const selected = languages.some((l) => l.code === language.code);
            return (
              <button
                type="button"
                className={`flex w-full items-center justify-between gap-3 rounded-lg px-4 py-3 text-left transition-colors ${
                  selected ? "bg-accent/20 border border-accent" : "bg-default border border-transparent hover:bg-default/50"
                }`}
                onClick={() => toggle(language)}
              >
                <div className="flex flex-col">
                  <span className="font-medium text-default-foreground">{language.name}</span>
                  <span className="text-xs text-muted-foreground">{language.code}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">{language.regionCode ?? ""}</span>
                  {selected ? (
                    <span className="text-accent">{t("localOnboarding.languages.selected")}</span>
                  ) : null}
                </div>
              </button>
            );
          }}
        />

        <div className="mt-auto flex justify-end">
          <button
            type="button"
            className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            disabled={languages.length === 0}
            onClick={onContinue}
          >
            {t("localOnboarding.languages.continue", { count: languages.length })}
          </button>
        </div>
      </div>
    </div>
  );
}
