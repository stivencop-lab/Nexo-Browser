/* Conservative vetoes for optional AI heuristics and learned domains, never for explicit filter rules. */
(function() {
  'use strict';
  const HOSTS = new Set([
    'accounts.google.com', 'appleid.apple.com', 'microsoftonline.com', 'auth0.com', 'clerk.com', 'clerk.accounts.dev',
    'challenges.cloudflare.com', 'recaptcha.net', 'hcaptcha.com', 'stripe.com', 'paypal.com',
    'youtube.com', 'youtube-nocookie.com', 'youtu.be', 'vimeo.com', 'dailymotion.com', 'twitch.tv',
    'spotify.com', 'soundcloud.com', 'voe.sx', 'streamsb.net', 'sbembed.com', 'vidhide.com', 'lulustream.com', 'doodstream.com'
  ]);
  const MEDIA = /\.(?:m3u8|mpd|mp4|webm|m4s|ts|aac|mp3|ogg|wav|vtt|srt|ass|dfxp|ttml)$/i;
  const FUNCTIONAL_PATH = /(?:^|\/)(?:embed|player|watch|v|video|stream|e|d|oauth2?|authorize|sso|login|signin|captcha|recaptcha|hcaptcha|turnstile|checkout|payment)(?:[/._-]|$)/i;

  function isProtectedHost(host) {
    let candidate = String(host || '').toLowerCase().replace(/\.$/, '');
    while (candidate) {
      if (HOSTS.has(candidate)) return true;
      const dot = candidate.indexOf('.');
      if (dot < 0) break;
      candidate = candidate.slice(dot + 1);
    }
    return false;
  }

  function isProtectedUrl(value, base) {
    if (typeof value !== 'string' || !value || value.length > 4096) return false;
    try {
      const url = new URL(value, base);
      return /^https?:$/.test(url.protocol) &&
        (isProtectedHost(url.hostname) || MEDIA.test(url.pathname) || FUNCTIONAL_PATH.test(url.pathname));
    } catch (_) { return false; }
  }

  globalThis.PhantomFunctionalSafety = Object.freeze({ isProtectedHost, isProtectedUrl });
})();
