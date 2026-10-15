export type HardwareProfile = {
  platform: string;
  class: string;
  signals: {
    ramHint: string;
    diskHint: string;
    hasGpuRuntime: boolean;
    hasStreamingRuntime: boolean;
    cpuCores?: number;
    deviceMemoryGb?: number;
  };
};

function detectPlatform(): string {
  try {
    const fromElectron = (window as any).electronAPI?.getPlatform?.();
    if (typeof fromElectron === "string" && fromElectron) return fromElectron;
    if (fromElectron instanceof Promise) return "darwin";
  } catch { /* fall through */ }
  if (typeof navigator !== "undefined") {
    const ua = (navigator.userAgent ?? "").toLowerCase();
    if (ua.includes("mac")) return "darwin";
    if (ua.includes("win")) return "win32";
    if (ua.includes("linux")) return "linux";
  }
  return "darwin";
}

function classifyRam(deviceMemoryGb?: number, cores?: number): string {
  if (typeof deviceMemoryGb === "number") {
    if (deviceMemoryGb <= 4) return "low";
    if (deviceMemoryGb >= 16) return "enthusiast";
    return "mainstream";
  }
  if (typeof cores === "number") {
    if (cores <= 4) return "low";
    if (cores >= 12) return "enthusiast";
  }
  return "mainstream";
}

export function buildHardwareProfile(): HardwareProfile {
  if (typeof window === "undefined") {
    return {
      platform: "darwin",
      class: "mainstream",
      signals: { ramHint: "mainstream", diskHint: "mainstream", hasGpuRuntime: false, hasStreamingRuntime: false },
    };
  }
  const platform = detectPlatform();
  const nav = navigator as any;
  const cpuCores = typeof nav?.hardwareConcurrency === "number" ? nav.hardwareConcurrency : undefined;
  const deviceMemoryGb = typeof nav?.deviceMemory === "number" ? nav.deviceMemory : undefined;
  const ramHint = classifyRam(deviceMemoryGb, cpuCores);
  const hardwareClass = ramHint;

  // GPU / streaming runtimes are probed async elsewhere (detectGpu, sherpa
  // availability); default false here and let callers upgrade the profile.
  return {
    platform,
    class: hardwareClass,
    signals: {
      ramHint,
      diskHint: "mainstream",
      hasGpuRuntime: false,
      hasStreamingRuntime: platform !== "linux",
      cpuCores,
      deviceMemoryGb,
    },
  };
}
