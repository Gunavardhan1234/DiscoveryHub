/**
 * Development environment. `ng serve` uses the dev-server proxy (proxy.conf.json)
 * to forward `/api/**` to the local api-gateway, so `apiUrl` stays same-origin.
 */
export const environment = {
  production: false,
  apiUrl: '',
};
