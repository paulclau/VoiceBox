const assert = require("node:assert/strict");
const test = require("node:test");
const React = require("react");
const { createRoot } = require("react-dom/client");
const {
  createRendererServer,
  installBrowserGlobals,
  installHookDom,
} = require("../lib/rendererTestHarness");

// The assistant chord belongs in the voiceAgent slot (store key voiceAgentKey),
// never on the dictation updateHotkey channel — sending it there would clobber
// the just-registered dictation hotkey and leave the AssistantPanel unaware.

function findElement(node, predicate) {
  if (Array.isArray(node)) {
    for (const child of node) {
      const match = findElement(child, predicate);
      if (match) return match;
    }
    return null;
  }
  if (!node || typeof node !== "object") return null;
  return predicate(node) ? node : findElement(node.props?.children, predicate);
}

function textContent(node) {
  if (Array.isArray(node)) return node.map(textContent).join("");
  if (node && typeof node === "object") return textContent(node.props?.children);
  return node == null || typeof node === "boolean" ? "" : String(node);
}

async function createActivationHarness(t) {
  let unmount = async () => {};
  t.after(() => unmount());
  const updateHotkeyCalls = [];
  installBrowserGlobals(t, {
    window: {
      electronAPI: {
        getPlatform: () => "linux",
        updateHotkey: async (hotkey) => {
          updateHotkeyCalls.push(hotkey);
          return { success: true };
        },
        getEffectiveDefaultHotkey: async () => "Control+Super",
        getHotkeyModeInfo: async () => ({ supportsPushToTalk: false }),
        // No updateVoiceAgentHotkey bridge: the store takes its local
        // fallback path (localStorage + store), which is what we assert.
      },
    },
  });
  const container = installHookDom(t);
  const vite = await createRendererServer(t, {
    cachePrefix: "openwhispr-local-onboarding-activation-test-",
    noExternal: ["react-i18next"],
    mockModules: {
      "react-i18next": `
        const t = (key) => key;
        export function useTranslation() { return { t }; }
        export const initReactI18next = { type: "3rdParty", init() {} };
      `,
    },
  });
  const { default: ActivationStep } = await vite.ssrLoadModule(
    "/components/localOnboarding/ActivationStep.tsx"
  );
  let tree;
  const continued = [];
  function Harness() {
    tree = ActivationStep({
      initial: {
        dictationMode: "tap",
        dictationHotkey: "",
        assistantHotkey: "",
        assistantEnabled: true,
      },
      saved: null,
      loading: false,
      saving: false,
      error: null,
      canRegister: true,
      onContinue: (choice) => continued.push(choice),
    });
    return null;
  }
  const root = createRoot(container);
  await React.act(async () => {
    root.render(React.createElement(Harness));
  });
  unmount = async () => {
    await React.act(async () => root.unmount());
  };

  const clickButton = async (label) => {
    const button = findElement(
      tree,
      (node) => typeof node.props?.onClick === "function" && textContent(node) === label
    );
    assert.ok(button, `button "${label}" is available`);
    await React.act(async () => {
      await button.props.onClick();
    });
  };

  return { get tree() { return tree; }, clickButton, updateHotkeyCalls, continued };
}

test("continue registers dictation on its channel and stores the assistant chord in voiceAgentKey", async (t) => {
  const h = await createActivationHarness(t);
  // Linux preset "Control+Super" formats to "Ctrl+Super". The assistant
  // chord is required too (empty initial state): "Ctrl+Alt+Space" only
  // appears in the assistant preset row.
  await h.clickButton("Ctrl+Super");
  await h.clickButton("Ctrl+Alt+Space");
  await h.clickButton("localOnboarding.activation.continue");

  assert.deepEqual(h.updateHotkeyCalls, ["Control+Super"]);
  assert.equal(
    globalThis.localStorage.getItem("voiceAgentKey"),
    "Control+Alt+Space",
    "assistant chord persisted to the voiceAgent slot, not the dictation channel"
  );
  assert.equal(h.continued.length, 1, "onContinue fires once");
  assert.equal(h.continued[0].dictationHotkey, "Control+Super");
  assert.equal(h.continued[0].assistantHotkey, "Control+Alt+Space");
});

test("skip forwards choices without touching either hotkey channel", async (t) => {
  const h = await createActivationHarness(t);
  await h.clickButton("Ctrl+Super");
  await h.clickButton("localOnboarding.activation.skip");

  assert.deepEqual(h.updateHotkeyCalls, [], "skip registers nothing");
  assert.equal(h.continued.length, 1, "onContinue still fires");
  assert.equal(h.continued[0].dictationHotkey, "Control+Super");
});
