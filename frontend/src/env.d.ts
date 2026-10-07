/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}

interface ImportMetaEnv {
  /** Base URL of the backend, e.g. https://proppilot-api.onrender.com. Empty = same origin (dev proxy). */
  readonly VITE_API_URL?: string
}
