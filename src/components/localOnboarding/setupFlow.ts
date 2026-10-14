export { default as LocalOnboardingShell } from "./OnboardingShell";
export type { SetupStepId } from "./OnboardingShell";

export const LOCAL_ONBOARDING_STEPS = [
  "detection",
  "languages",
  "stack",
  "download",
  "micTest",
  "activation",
] as const;

export type LocalOnboardingStepId = (typeof LOCAL_ONBOARDING_STEPS)[number];
