const assert = require("node:assert/strict");
const test = require("node:test");
const React = require("react");
const { createRoot } = require("react-dom/client");
const {
  createRendererServer,
  installBrowserGlobals,
  installHookDom,
} = require("../lib/rendererTestHarness");

// Exercises the rewritten useDownloadCoordinator through the real
// localOnboarding DownloadStep: start() must actually drive downloads (the
// old stale-closure version silently no-op'd), failures must land in `failed`
// with a retry path, and onProgress must report real snapshots.

const PARAKEET = "parakeet-tdt-0.6b-v3";
const WHISPER = "whisper:base";

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

async function createDownloadHarness(t, summary) {
  let unmount = async () => {};
  t.after(() => unmount());
  const listeners = { parakeet: new Set(), whisper: new Set() };
  const requests = new Map();
  const subscribe = (family) => (listener) => {
    listeners[family].add(listener);
    return () => listeners[family].delete(listener);
  };
  const startDownload = (family) => (modelName) =>
    new Promise((resolve) => requests.set(modelName, { family, resolve }));
  installBrowserGlobals(t, {
    window: {
      electronAPI: {
        getPlatform: () => "linux",
        downloadParakeetModel: startDownload("parakeet"),
        downloadWhisperModel: startDownload("whisper"),
        onParakeetDownloadProgress: subscribe("parakeet"),
        onWhisperDownloadProgress: subscribe("whisper"),
      },
    },
  });
  const container = installHookDom(t);
  const vite = await createRendererServer(t, {
    cachePrefix: "openwhispr-local-onboarding-download-test-",
    noExternal: ["react-i18next"],
    mockModules: {
      "react-i18next": `
        const t = (key) => key;
        export function useTranslation() { return { t }; }
        export const initReactI18next = { type: "3rdParty", init() {} };
      `,
    },
  });
  const { default: DownloadStep } = await vite.ssrLoadModule(
    "/components/localOnboarding/DownloadStep.tsx"
  );
  const { isComplete } = await vite.ssrLoadModule("/lib/localSetupState.ts");
  let tree;
  let continued = 0;
  const snapshots = [];
  function Harness() {
    tree = DownloadStep({
      summary,
      loading: false,
      error: null,
      onProgress: (p) => snapshots.push(JSON.parse(JSON.stringify(p))),
      onContinue: () => {
        continued += 1;
      },
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

  const emit = async (family, event) => {
    await React.act(async () => {
      for (const listener of listeners[family]) listener({}, event);
    });
  };
  const progress = async (modelId, percentage, phase = "progress") => {
    const request = requests.get(modelId.replace(/^whisper:/, ""));
    assert.ok(request, `download ${modelId} started`);
    await emit(request.family, {
      model: request.family === "parakeet" ? modelId : modelId.replace(/^whisper:/, ""),
      type: phase,
      percentage,
      downloaded_bytes: percentage,
      total_bytes: 100,
    });
  };
  const resolveDownload = async (modelId, result = { success: true }) => {
    const key = modelId.replace(/^whisper:/, "");
    const request = requests.get(key);
    assert.ok(request, `download ${modelId} started`);
    await React.act(async () => {
      request.resolve({ model: key, ...result });
    });
  };
  const clickButton = async (label) => {
    const button = findElement(
      tree,
      (node) => typeof node.props?.onClick === "function" && textContent(node) === label
    );
    assert.ok(button, `button "${label}" is available`);
    await React.act(async () => button.props.onClick());
  };
  const lastSnapshot = () => snapshots[snapshots.length - 1];

  return {
    get tree() {
      return tree;
    },
    text: () => textContent(tree),
    requests,
    snapshots,
    lastSnapshot,
    continued: () => continued,
    isComplete,
    emit,
    progress,
    resolveDownload,
    clickButton,
  };
}

const SUMMARY = [
  { modelId: PARAKEET, label: "Parakeet", description: "", sizeLabel: "680 MB" },
  { modelId: WHISPER, label: "Whisper base", description: "", sizeLabel: "142 MB" },
];

test("starting the download step drives both downloads to done", async (t) => {
  const h = await createDownloadHarness(t, SUMMARY);
  // The auto-start effect must have kicked off the first download (sequential).
  assert.ok(h.requests.has(PARAKEET), "parakeet download started");
  assert.ok(
    h.snapshots.length > 0 && h.lastSnapshot().active.length > 0,
    "onProgress reports a real non-empty snapshot"
  );
  assert.ok(
    h.snapshots.every((s) => Array.isArray(s.active) && Array.isArray(s.done)),
    "every snapshot has the real progress shape"
  );

  await h.progress(PARAKEET, 42);
  assert.match(h.text(), /42/, "live percentage renders");

  await h.resolveDownload(PARAKEET);
  assert.ok(h.requests.has("base"), "whisper download starts after parakeet completes");
  await h.resolveDownload(WHISPER);

  const done = h.lastSnapshot();
  assert.equal(done.active.length, 0, "nothing left active");
  assert.equal(done.done.length, 2, "both models done");
  assert.ok(h.isComplete(done, SUMMARY), "shell completion predicate holds");
  assert.match(h.text(), /localOnboarding\.download\.allInstalled/, "continue CTA renders");
  await h.clickButton("localOnboarding.download.allInstalled");
  assert.equal(h.continued(), 1, "onContinue fires");
});

test("a failed download lands in failed with a retry path", async (t) => {
  const h = await createDownloadHarness(t, SUMMARY);
  await h.resolveDownload(PARAKEET, { success: false, error: "boom" });
  const failed = h.lastSnapshot();
  assert.equal(failed.failed.length, 1, "failure recorded");
  assert.equal(failed.failed[0].error, "boom", "error preserved");
  assert.match(h.text(), /boom/, "error renders");
  assert.match(h.text(), /localOnboarding\.download\.retry/, "retry renders");
  assert.ok(!h.isComplete(failed, SUMMARY), "completion predicate stays false");
});

test("cancel resets to idle instead of hanging or blaming unstarted items", async (t) => {
  const h = await createDownloadHarness(t, SUMMARY);
  assert.ok(h.requests.has(PARAKEET), "download started");
  await h.clickButton("localOnboarding.download.cancel");
  const reset = h.lastSnapshot();
  assert.equal(reset.active.length, 0, "nothing left active after cancel");
  assert.equal(reset.failed.length, 0, "unstarted items are not reported as failed");
  assert.equal(reset.done.length, 0, "nothing done after cancel");
  assert.match(h.text(), /localOnboarding\.download\.download/, "per-item Download offered again");
});
