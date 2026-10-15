import modelRegistry from "../models/modelRegistryData.json";

export type LocalStackModel = {
  modelId: string;
  displayName: string;
  description: string;
  sizeLabel: string;
  runtime?: string;
  quantization?: string;
  downloaded?: boolean;
};

export type LocalStackRecommendation = {
  choice: {
    transcriptionModelId: string;
    assistantModelId: string;
  };
  transcriptionOptions: LocalStackModel[];
  assistantOptions: LocalStackModel[];
  translationPairs: Array<{ from: string; to: string }>;
};

function pickTranscriptionOptions(platform: string, hardwareClass: string, languages: string[], wantStreaming: boolean): LocalStackModel[] {
  const items: LocalStackModel[] = [];
  const parakeetModels = modelRegistry.parakeetModels as Record<string, any> | undefined;
  const whisperModels = modelRegistry.whisperModels as Record<string, any> | undefined;

  if (parakeetModels) {
    for (const [id, meta] of Object.entries(parakeetModels)) {
      const supportedLanguages: string[] = meta.supportedLanguages ?? [];
      const matchesLanguages = languages.length === 0 || languages.some((l) => supportedLanguages.includes(l));
      if (!matchesLanguages) continue;
      const runtime = meta.runtime ?? (wantStreaming ? "online" : "offline");
      const chip = runtime === "online" && wantStreaming ? "online" : "offline";
      if (wantStreaming && chip !== "online") continue;
      items.push({
        modelId: id,
        displayName: meta.name ?? id,
        description: (meta.descriptionKey ? "" : meta.description ?? id),
        sizeLabel: meta.size ?? meta.sizeMb ? `${(meta.sizeMb ?? Math.round((meta.expectedSizeBytes ?? 0) / 1_000_000))} MB` : "",
        runtime: chip,
      });
    }
  }

  if (whisperModels && languages.some((l) => l === "en" || l === "en-US")) {
    for (const [id, meta] of Object.entries(whisperModels)) {
      if (id !== "small" && id !== "base") continue;
      items.push({
        modelId: `whisper:${id}`,
        displayName: (meta.name ?? id),
        description: meta.description ?? "",
        sizeLabel: meta.size ?? "",
      });
    }
  }

  return items.sort((a, b) => (a.sizeLabel ?? "").localeCompare(b.sizeLabel ?? ""));
}

function pickAssistantOptions(platform: string, hardwareClass: string, languages: string[]): LocalStackModel[] {
  const items: LocalStackModel[] = [];
  const localProviders = modelRegistry.localProviders as Array<any> | undefined;

  if (!localProviders) return items;

  const candidates: Array<{ modelId: string; displayName: string; description: string; sizeLabel: string; quantization?: string }> = [];

  for (const family of localProviders) {
    for (const model of (family.models ?? [])) {
      const id = model.id as string;
      const sizeBytes = model.sizeBytes as number | undefined;
      const sizeLabel = model.size as string | undefined;
      const quant = model.quantization as string | undefined;
      candidates.push({
        modelId: id,
        displayName: (model.name ?? id),
        description: (model.descriptionKey ? "" : model.description ?? id),
        sizeLabel: sizeLabel ?? (sizeBytes ? `${(sizeBytes / 1_000_000_000).toFixed(1)} GB` : ""),
        quantization: quant,
      });
    }
  }

  // Pick a small-cap set for lower hardware classes.
  const byClass: Record<string, number> = {
    low: 2,
    mainstream: 4,
    enthusiast: 6,
  };

  const limit = byClass[hardwareClass] ?? 4;
  return candidates
    .filter((m) => m.sizeLabel)
    .sort((a, b) => (a.sizeLabel ?? "").localeCompare(b.sizeLabel ?? ""))
    .slice(0, limit)
    .map((m) => ({
      modelId: m.modelId,
      displayName: m.displayName,
      description: m.description,
      sizeLabel: m.sizeLabel,
      quantization: m.quantization,
    }));
}

export function getRecommendedLocalStack(
  platform: string,
  hardwareClass: string,
  languages: string[],
  wantStreaming: boolean,
): LocalStackRecommendation {
  const transcriptionOptions = pickTranscriptionOptions(platform, hardwareClass, languages, wantStreaming);
  const assistantOptions = pickAssistantOptions(platform, hardwareClass, languages);

  const transcriptionModelId =
    transcriptionOptions.find((m) => m.runtime === (wantStreaming ? "online" : "offline"))?.modelId ??
    transcriptionOptions[0]?.modelId ??
    "parakeet-tdt-0.6b-v3";

  const assistantModelId =
    assistantOptions.find((m) => /q4/i.test(m.sizeLabel ?? ""))?.modelId ??
    assistantOptions[assistantOptions.length - 1]?.modelId ??
    "qwen3.5-9b-q4_k_m";

  return {
    choice: {
      transcriptionModelId,
      assistantModelId,
    },
    transcriptionOptions,
    assistantOptions,
    translationPairs: languages.length > 0 ? [{ from: languages[0], to: "en" }] : [],
  };
}
