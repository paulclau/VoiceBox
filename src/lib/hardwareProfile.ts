export type HardwareProfile = {
  platform: string;
  class: string;
  signals: {
    ramHint: string;
    diskHint: string;
    hasGpuRuntime: boolean;
    hasStreamingRuntime: boolean;
  };
};

export function buildHardwareProfile(): HardwareProfile {
  if (typeof window === "undefined") {
    return {
      platform: "darwin",
      class: "mainstream",
      signals: { ramHint: "mainstream", diskHint: "mainstream", hasGpuRuntime: false, hasStreamingRuntime: false },
    };
  }
  // Stretch file rules ignore extension, but a .ts file is fine here.
  const platform = (window as any).electronAPI?.getPlatform?.() ?? (() => {
    const ua = (navigator.userAgent ?? "").toLowerCase();
    if (ua.includes("mac")) return "darwin";
    if (ua.includes("win")) return "win32";
    if (ua.includes("linux")) return "linux";
    return "darwin";
  })();

  return {
    platform,
    class: "mainstream",
    signals: {
      ramHint: "mainstream",
      diskHint: "mainstream",
      hasGpuRuntime: false,
      hasStreamingRuntime: false,
    },
  };
}
