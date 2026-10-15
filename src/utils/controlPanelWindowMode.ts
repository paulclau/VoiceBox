export type ControlPanelWindowMode = "compact" | "restore";

export interface SettledControlPanelWindowState {
  isControlPanel: boolean;
  isLoading: boolean;
  isWaitingForPolicyStart: boolean;
  showOnboarding: boolean;
  /** Local-first setup shell — owns window sizing like legacy onboarding. */
  showLocalSetup?: boolean;
  needsReauth: boolean;
}

export function resolveSettledControlPanelWindowMode(
  state: SettledControlPanelWindowState
): ControlPanelWindowMode | null {
  if (
    !state.isControlPanel ||
    state.isLoading ||
    state.isWaitingForPolicyStart ||
    state.showOnboarding ||
    state.showLocalSetup
  ) {
    return null;
  }

  return state.needsReauth ? "compact" : "restore";
}
