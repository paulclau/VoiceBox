import { getRecommendedLocalStack } from "../lib/recommendedLocalStack";
import type { Platform } from "../utils/platform";

export type HardwareProfile = {
  platform: Platform;
  class: "low" | "mainstream" | "enthusiast";
  signals: {
    ramHint: "low" | "mainstream" | "high";
    diskHint: "low" | "mainstream" | "high";
    hasGpuRuntime: boolean;
    hasStreamingRuntime: boolean;
  };
};

export function buildHardwareProfile(): HardwareProfile {
  const platform = (typeof window !== "undefined" && window.electronAPI?.getPlatform?.() ??
    (() => {
      const ua = (typeof navigator !== "undefined" ? navigator.userAgent ?? "" : "").toLowerCase();
      if (ua.includes("mac")) return "darwin";
      if (ua.includes("win")) return "win32";
      if (ua.includes("linux")) return "linux";
      return "darwin";
    })() as Platform);

  // Conservative defaults when main has not exposed richer signals yet.
  const ramHint: "low" | "mainstream" | "high" = "mainstream";
  const diskHint: "low" | "mainstream" | "high" = "mainstream";
  const hasGpuRuntime = false;
  const hasStreamingRuntime = false;

  const class_: "low" | "mainstream" | "enthusiast" =
    ramHint === "low" || diskHint === "low" ? "low"
      : ramHint === "high" && diskHint === "high" ? "enthusiast"
      : "mainstream";

  return { platform, class: class_, signals: { ramHint, diskHint, hasGpuRuntime, hasStreamingRuntime } };
}

export function recommendFromProfile(profile: HardwareProfile, spokenLanguages: string[], wantStreaming: boolean) {
  const stack = getRecommendedLocalStack(profile.platform, profile.class, spokenLanguages, wantStreaming);
  return { profile, stack };
}
