/**
 * Rules shared by the global fetch/XHR 401 fallbacks.
 *
 * Playback asset URLs carry a short-lived playback token rather than the
 * login access token. A 401 from these endpoints is therefore an asset
 * failure, not proof that the user session expired.
 */

export function isPlaybackAssetEndpoint(url: string | undefined): boolean {
  if (!url) return false;

  let pathname: string;
  try {
    // URL() also strips query strings and fragments from absolute URLs.
    // Relative URLs are resolved against the current page when available.
    pathname = new URL(url, window.location.origin).pathname;
  } catch {
    pathname = url.split(/[?#]/, 1)[0];
  }

  // Keep this deliberately narrow: only HLS/DASH playback assets are
  // exempted, never /playback metadata or arbitrary API endpoints.
  return /\/api\/v1\/playback\/[^/]+\/(?:segment(?:\/.*)?|playlist(?:\.m3u8)?|key)\/?$/i.test(pathname);
}

/**
 * Decide whether a delayed global 401 fallback should still log out.
 * Axios refresh changes access_token before this fallback's timer runs; in
 * that case the original 401 has already been recovered and must be ignored.
 */
export function shouldHandleDelayed401(
  url: string | undefined,
  tokenAtResponse: string | null,
  currentToken: string | null,
): boolean {
  return !isPlaybackAssetEndpoint(url) && tokenAtResponse === currentToken;
}
