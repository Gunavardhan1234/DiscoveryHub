/**
 * Production environment. The SPA is served by nginx which reverse-proxies
 * `/api/**` to the api-gateway, so `apiUrl` is same-origin (empty string).
 */
export const environment = {
  production: true,
  apiUrl: '',
};
