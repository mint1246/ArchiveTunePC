export function json(value: unknown, status = 200, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...headers,
    },
  });
}

export function error(message: string, status = 400): Response {
  return json({ error: message }, status);
}

export async function readJson<T>(request: Request): Promise<T> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new HttpError(415, "expected application/json");
  }
  try {
    return await request.json<T>();
  } catch {
    throw new HttpError(400, "invalid json");
  }
}

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function requireMethod(request: Request, method: string): void {
  if (request.method !== method) throw new HttpError(405, "method not allowed");
}
