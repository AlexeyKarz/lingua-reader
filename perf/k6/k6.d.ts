declare const __ENV: Record<string, string | undefined>;
declare const __ITER: number;

declare module 'k6' {
  export function check<T>(
    value: T,
    checks: Record<string, (value: T) => boolean>
  ): boolean;

  export function sleep(seconds: number): void;
}

declare module 'k6/http' {
  export type Response = {
    status: number;
    body: string;
    json(): unknown;
  };

  type Params = {
    headers?: Record<string, string>;
  };

  const http: {
    get(url: string, params?: Params): Response;
    post(url: string, body: string, params?: Params): Response;
  };

  export default http;
}
