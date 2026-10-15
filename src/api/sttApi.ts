export type LocalTranscriptionResult =
  | { ok: true; text: string }
  | { ok: false; error: string };

export async function transcribeLocalAudio(
  audio: ArrayBuffer | Blob | string,
  options?: { model?: "parakeet" | "whisper" },
): Promise<LocalTranscriptionResult> {
  const body = await toBuffer(audio);
  if (body.byteLength === 0) {
    return { ok: false, error: "empty_audio" };
  }

  if (options?.model === "whisper") {
    return runWhisper(body);
  }

  const parakeet = await runParakeet(body);
  if (parakeet.ok) return parakeet;
  const whisper = await runWhisper(body);
  return whisper.ok ? whisper : firstError(parakeet, whisper);
}

export async function transcribeLocalParakeet(
  audio: ArrayBuffer | Blob | string,
  options?: { model?: string },
): Promise<LocalTranscriptionResult> {
  const body = await toBuffer(audio);
  if (body.byteLength === 0) return { ok: false, error: "empty_audio" };
  return runParakeetWithModel(body, options?.model);
}

export async function transcribeLocalWhisper(
  audio: ArrayBuffer | Blob | string,
): Promise<LocalTranscriptionResult> {
  const body = await toBuffer(audio);
  if (body.byteLength === 0) return { ok: false, error: "empty_audio" };
  return runWhisper(body);
}

async function runParakeet(body: ArrayBuffer): Promise<LocalTranscriptionResult> {
  return runParakeetWithModel(body, "parakeet-tdt-0.6b-v3");
}

async function runParakeetWithModel(
  body: ArrayBuffer,
  model?: string,
): Promise<LocalTranscriptionResult> {
  try {
    const api = (window as any).electronAPI;
    const result = await api?.transcribeLocalParakeet?.(body, {
      model: model ?? "parakeet-tdt-0.6b-v3",
    });
    if (typeof result === "string") {
      const text = result.trim();
      return text ? { ok: true, text } : { ok: false, error: "parakeet_empty" };
    }
    if (result && typeof result === "object") {
      const text = typeof (result as any).text === "string" ? (result as any).text.trim() : "";
      if (text) return { ok: true, text };
      if ((result as any).success === false || (result as any).error) {
        return { ok: false, error: String((result as any).message ?? (result as any).error ?? "parakeet_empty") };
      }
      return { ok: false, error: "parakeet_empty" };
    }
    return { ok: false, error: "parakeet_unexpected" };
  } catch (error) {
    return { ok: false, error: normalizeError(error) };
  }
}

async function runWhisper(body: ArrayBuffer): Promise<LocalTranscriptionResult> {
  try {
    const api = (window as any).electronAPI;
    const result = await api?.transcribeLocalWhisper?.(body, {});
    if (typeof result === "string") {
      const text = result.trim();
      return text ? { ok: true, text } : { ok: false, error: "whisper_empty" };
    }
    if (result && typeof result === "object") {
      const text = typeof (result as any).text === "string" ? (result as any).text.trim() : "";
      if (text) return { ok: true, text };
      if ((result as any).success === false || (result as any).error) {
        return { ok: false, error: String((result as any).message ?? (result as any).error ?? "whisper_empty") };
      }
      return { ok: false, error: "whisper_empty" };
    }
    return { ok: false, error: "whisper_unexpected" };
  } catch (error) {
    return { ok: false, error: normalizeError(error) };
  }
}

function normalizeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    const parsed = JSON.parse(JSON.stringify(error));
    if (parsed?.message) return String(parsed.message);
    if (parsed?.error) return String(parsed.error);
    return JSON.stringify(parsed);
  } catch {
    return "local_transcription_failed";
  }
}

async function toBuffer(input: ArrayBuffer | Blob | string): Promise<ArrayBuffer> {
  if (typeof input === "string") {
    if (input.startsWith("data:")) {
      const comma = input.indexOf(",");
      if (comma >= 0) {
        const bytes = Buffer.from(input.slice(comma + 1), "base64");
        return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      }
    }
    return new TextEncoder().encode(input).buffer as ArrayBuffer;
  }
  if (input instanceof ArrayBuffer) {
    return input;
  }
  if (typeof Blob !== "undefined" && input instanceof Blob) {
    return input.arrayBuffer();
  }
  return new ArrayBuffer(0);
}

function firstError(a: LocalTranscriptionResult, b: LocalTranscriptionResult): LocalTranscriptionResult {
  if (!a.ok && !b.ok) {
    const bErr = (b as { ok: false; error: string }).error;
    const aErr = (a as { ok: false; error: string }).error;
    return { ok: false, error: bErr || aErr };
  }
  if (!a.ok) return a;
  return b;
}
