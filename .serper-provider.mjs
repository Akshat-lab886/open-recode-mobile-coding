/**
 * Serper (google.serper.dev) search provider for the DSH web seam.
 *
 * Two things this adapter exists to solve:
 *  1. Serper speaks a different protocol from the shipped DeepSeek provider --
 *     POST /search with an X-API-KEY header and a {q} body, returning
 *     {organic:[{title,link,snippet,date}]}. The DeepSeek provider calls an
 *     Anthropic-compatible /messages endpoint, so pointing it at serper.dev
 *     yields 404.
 *  2. Serper is slow to connect from some networks (measured 7-18s here).
 *     Node's global fetch runs on undici, whose hard-coded 10s connect
 *     timeout aborts before the server answers, so this uses node:https,
 *     which has no such cap, with a caller-controlled deadline.
 */
import https from 'node:https';
import z from '@deepseek-ai/schemastery';
import { WebError } from '@deepseek-ai/dsh-web';

export const name = 'web-search-serper';
export const inject = ['web'];

export const SERPER_PROVIDER_ID = 'serper';

export const Config = z.object({
  providerId: z.string().default(SERPER_PROVIDER_ID),
  apiKey: z.string(),
  apiKeyEnv: z.string().default('SERPER_API_KEY'),
  baseURL: z.string().default('https://google.serper.dev'),
  timeoutMs: z.number().default(45000),
});

const MAX_NUM = 20;

function field(v) {
  return v && typeof v.get === 'function' ? v.get() : v;
}

function resolveApiKey(config) {
  const literal = field(config.apiKey);
  if (typeof literal === 'string' && literal.length > 0) return literal;
  const envName = field(config.apiKeyEnv) || 'SERPER_API_KEY';
  return process.env[envName] || '';
}

function extractContent(data) {
  const parts = [];
  const box = data.answerBox;
  if (box) {
    if (box.answer) parts.push(String(box.answer));
    else if (box.snippet) parts.push(String(box.snippet).replace(/\s+/g, ' ').trim());
  }
  const kg = data.knowledgeGraph;
  if (kg && kg.description) parts.push(String(kg.description));
  return parts.length ? parts.join('\n\n') : undefined;
}

/** Minimal JSON POST over node:https. No undici connect cap; caller sets the deadline. */
function postJson(url, payload, headers, timeoutMs, signal) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const text = JSON.stringify(payload);
    const req = https.request({
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      method: 'POST',
      headers: { ...headers, 'Content-Length': Buffer.byteLength(text) },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        statusCode: res.statusCode || 0,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });

    const timer = setTimeout(() => {
      req.destroy(new Error('Serper request exceeded ' + timeoutMs + 'ms'));
    }, timeoutMs);

    const onAbort = () => req.destroy(new Error('cancelled'));
    if (signal) {
      if (signal.aborted) {
        clearTimeout(timer);
        reject(new Error('cancelled'));
        return;
      }
      signal.addEventListener('abort', onAbort, { once: true });
    }

    req.on('error', (err) => {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', onAbort);
      reject(err);
    });
    req.on('close', () => {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', onAbort);
    });

    req.end(text);
  });
}

class SerperSearchProvider {
  constructor(options) {
    this.id = options.providerId;
    this.options = options;
  }

  available() {
    return this.options.apiKey().length > 0;
  }

  async search(request, signal) {
    const key = this.options.apiKey();
    if (!key) {
      throw new WebError(
        'Serper search has no API key; export ' + this.options.apiKeyEnv +
        ' before starting dsh, or set a literal apiKey in the web-search-serper config.',
        'WEB_PROVIDER_CREDENTIAL_MISSING'
      );
    }

    const body = { q: request.query };
    if (typeof request.maxResults === 'number') {
      body.num = Math.max(1, Math.min(request.maxResults, MAX_NUM));
    }

    const url = this.options.baseURL.replace(/\/+$/, '') + '/search';
    let res;
    try {
      res = await postJson(
        url,
        body,
        { 'X-API-KEY': key, 'Content-Type': 'application/json' },
        this.options.timeoutMs,
        signal
      );
    } catch (cause) {
      const msg = cause && cause.message ? cause.message : String(cause);
      throw new WebError(
        'Serper search request to ' + url + ' failed: ' + msg,
        'WEB_PROVIDER_ERROR',
        cause === undefined ? undefined : { cause }
      );
    }

    if (res.statusCode < 200 || res.statusCode >= 300) {
      const detail = res.body.slice(0, 200);
      const hint = res.statusCode === 401 || res.statusCode === 403
        ? ' The Serper API key looks invalid or unauthorized.'
        : res.statusCode === 429
          ? ' Serper rate limit or quota reached.'
          : '';
      throw new WebError(
        'Serper search failed with HTTP ' + res.statusCode + '.' + hint +
        (detail ? ' Response: ' + detail : ''),
        'WEB_PROVIDER_ERROR'
      );
    }

    let data;
    try {
      data = JSON.parse(res.body);
    } catch (cause) {
      throw new WebError(
        'Serper returned a non-JSON body: ' + res.body.slice(0, 200),
        'WEB_PROVIDER_ERROR',
        cause === undefined ? undefined : { cause }
      );
    }

    const organic = Array.isArray(data.organic) ? data.organic : [];
    const sources = organic
      .filter((o) => o && typeof o.link === 'string' && o.link.length > 0)
      .map((o) => {
        const source = { url: o.link };
        if (o.title) source.title = String(o.title);
        if (o.snippet) source.snippet = String(o.snippet);
        if (o.date) source.publishedAt = String(o.date);
        return source;
      });

    return { content: extractContent(data), sources, truncated: false };
  }
}

export function apply(ctx, config) {
  const provider = new SerperSearchProvider({
    providerId: field(config.providerId),
    apiKey: () => resolveApiKey(config),
    apiKeyEnv: field(config.apiKeyEnv),
    baseURL: field(config.baseURL),
    timeoutMs: field(config.timeoutMs),
  });
  const dispose = ctx.web.registerSearchProvider(provider);
  ctx.on('dispose', dispose);
  if (ctx.logger) {
    ctx.logger.info(
      'web-search-serper: registered provider %s (usable: %s)',
      provider.id,
      provider.available()
    );
  }
}